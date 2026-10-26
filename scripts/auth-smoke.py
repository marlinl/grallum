#!/usr/bin/env python3
"""Verify browser JWT, CSRF, refresh replay and current account state against an isolated instance."""
import argparse
import base64
import concurrent.futures
import http.cookiejar
import http.cookies
import json
import os
from pathlib import Path
import re
import secrets
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep status codes visible so a login redirect cannot hide an authorization failure."""

    def redirect_request(self, request, response, code, message, headers, new_url):
        """Return no redirect request; callers assert each boundary explicitly."""
        return None


class Browser:
    """Hold isolated host-only cookies without logging credentials or token values."""

    def __init__(self, base):
        """Create a fresh cookie jar and a direct, non-redirecting HTTP opener."""
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPCookieProcessor(self.jar))

    def request(self, path, method='GET', data=None, headers=None, expected=200):
        """Send a bounded request and assert only safe status and route information."""
        headers = dict(headers or {})
        if isinstance(data, dict):
            data = urllib.parse.urlencode(data).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        body = response.read()
        assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
        return response.headers, body

    def csrf(self, path='/login'):
        """Load a server-rendered CSRF token without exposing it in diagnostic output."""
        _, body = self.request(path)
        return re.search(rb'name="_csrf"[^>]*value="([^"]+)"', body).group(1).decode()

    def login(self, username, password):
        """Authenticate a no-JavaScript browser form and return its access credential privately."""
        self.request('/login', 'POST', {'username': username, 'password': password, '_csrf': self.csrf()}, expected=302)
        self.csrf('/')
        return next(cookie.value for cookie in self.jar if cookie.name.endswith('grallum-access'))

    def cookies(self, path):
        """Build the exact outgoing cookie header for a controlled replay or concurrency test."""
        request = urllib.request.Request(self.base + path)
        self.jar.add_cookie_header(request)
        return request.get_header('Cookie')


def main():
    """Exercise real persisted authentication state while creating only uniquely named test users."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://localhost:8080')
    args = parser.parse_args()
    password = os.environ.get('ADMIN_PASSWORD') or Path('.local/admin-password').read_text().strip()
    username = os.environ.get('ADMIN_USERNAME', 'admin')
    browser = Browser(args.url)
    browser.request('/login', 'POST', {'username': username, 'password': password}, expected=403)
    token = browser.login(username, password)
    assert all(cookie.name != 'JSESSIONID' for cookie in browser.jar)
    browser.request('/api/repositories')
    csrf = browser.csrf('/')
    browser.request('/assets/style.css')
    browser.request('/repositories', 'POST', {'registry': 'private', 'name': 'auth/' + secrets.token_hex(8), '_csrf': csrf}, expected=302)
    browser.request('/repositories', 'POST', {'registry': 'private', 'name': 'forbidden'}, expected=403)
    browser.request('/auth/refresh', 'POST', {}, expected=403)
    browser.request('/v2/', expected=401)
    browser.request('/api/repositories', headers={'Authorization': 'Bearer invalid'}, expected=401)
    basic = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
    browser.request('/api/repositories', headers={'Authorization': basic}, expected=401)
    browser.request('/v2/', headers={'Authorization': basic}, expected=401)
    csrf = browser.csrf('/')
    old_cookie = browser.cookies('/auth/refresh')

    def concurrent_rotation():
        """Return either the successful rotation or committed replay rejection without following redirects."""
        request = urllib.request.Request(args.url + '/auth/refresh', data=urllib.parse.urlencode({'_csrf': csrf}).encode(), headers={'Cookie': old_cookie, 'Content-Type': 'application/x-www-form-urlencoded'}, method='POST')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        response.read()
        return response.status, response.headers

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(lambda _: concurrent_rotation(), range(2)))
    assert sorted(status for status, _ in outcomes) == [302, 401], 'refresh must be consumed once'
    winner = next(headers for status, headers in outcomes if status == 302)
    cookies = http.cookies.SimpleCookie()
    for value in winner.get_all('Set-Cookie'):
        cookies.load(value)
    winning_token = next(value.value for key, value in cookies.items() if key.endswith('grallum-access'))
    anonymous = Browser(args.url)
    anonymous.request('/api/repositories', headers={'Authorization': 'Bearer ' + token}, expected=401)
    anonymous.request('/api/repositories', headers={'Authorization': 'Bearer ' + winning_token}, expected=401)
    browser = Browser(args.url)
    token = browser.login(username, password)
    browser.request('/auth/logout', 'POST', {'_csrf': browser.csrf('/')}, expected=302)
    anonymous.request('/api/repositories', headers={'Authorization': 'Bearer ' + token}, expected=401)
    machine = Browser(args.url)
    _, payload = machine.request('/api/auth/token', 'POST', json.dumps({'username': username, 'password': password}).encode(), {'Content-Type': 'application/json'})
    admin_token = json.loads(payload)['access_token']
    user = 'auth-' + secrets.token_hex(8)
    user_password = secrets.token_urlsafe(24)
    admin_headers = {'Authorization': 'Bearer ' + admin_token, 'Content-Type': 'application/json'}
    machine.request('/api/users', 'POST', json.dumps({'username': user, 'password': user_password, 'admin': True}).encode(), admin_headers, expected=201)
    other = Browser(args.url)
    other_token = other.login(user, user_password)
    other.request('/api/users')
    browser = Browser(args.url)
    browser.login(username, password)
    browser.request('/api/repositories', headers={'Authorization': 'Bearer ' + other_token}, expected=401)
    machine.request('/api/users/' + user, 'PUT', json.dumps({'enabled': True, 'admin': False}).encode(), admin_headers)
    other.request('/api/users', expected=403)
    other.request('/api/repositories')
    machine.request('/api/users/' + user, 'PUT', json.dumps({'enabled': True, 'admin': False, 'password': secrets.token_urlsafe(24)}).encode(), admin_headers)
    other.request('/api/repositories', expected=401)
    print('PASS browser CSRF across static resources, audience and Basic rejection, conflicting identities, concurrent refresh replay revocation, logout, current roles and password invalidation')


if __name__ == '__main__':
    main()
