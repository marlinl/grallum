#!/usr/bin/env python3
"""Exercise a running Grallum instance with real PostgreSQL and Distribution.

Creates uniquely named smoke repositories/users/images. Set ADMIN_PASSWORD or
use the ignored .local/admin-password file. --age-fixtures updates only this
run's observations through docker compose to test retention deletion.
"""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class Client:
    """HTTP client with explicit management JWT and repository-scoped registry JWT credentials."""

    def __init__(self, base, username=None, password=None):
        """Retain explicit test credentials and isolated cookies; construction performs no network I/O."""
        self.base = base
        self.username, self.password = username, password
        self.auth = None
        self.registry_tokens = {}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, method='GET', body=None, headers=None, expected=200, csrf=True):
        """Send a bounded request with the correct audience and assert its non-secret status result."""
        values = dict(headers or {})
        route = urllib.parse.urlparse(path).path
        if route.startswith('/v2/'):
            values.setdefault('Accept', 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json')
            if self.username:
                match = re.match(r'^/v2/(.+)/(?:manifests|blobs|tags)/', route)
                name = match.group(1) if match else ''
                if name not in self.registry_tokens:
                    query = {'service': 'grallum-registry'}
                    if name:
                        query['scope'] = 'repository:' + name + ':pull,push,delete'
                    basic = 'Basic ' + base64.b64encode(f'{self.username}:{self.password}'.encode()).decode()
                    payload, _ = self.request('/auth/registry/token?' + urllib.parse.urlencode(query), headers={'Authorization': basic})
                    self.registry_tokens[name] = json.loads(payload)['token']
                values.setdefault('Authorization', 'Bearer ' + self.registry_tokens[name])
        elif self.auth and not route.startswith('/auth/'):
            values.setdefault('Authorization', self.auth)
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            values['Content-Type'] = 'application/json'
        url = urllib.parse.urljoin(self.base, path)
        try:
            response = self.opener.open(urllib.request.Request(url, data=body, headers=values, method=method), timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        payload = response.read()
        assert response.status == expected, f'{method} {route}: expected {expected}, got {response.status}: {payload[:400]!r}'
        return payload, response.headers

    def json(self, path, method='GET', body=None, expected=200):
        """Exchange a typed JSON management message and return its decoded response."""
        payload, _ = self.request(path, method, body, expected=expected)
        return json.loads(payload) if payload else None

    def login(self):
        """Exchange explicit JSON credentials for a management token without creating browser cookies."""
        token = self.json('/api/auth/token', 'POST', {'username': self.username, 'password': self.password})
        self.auth = 'Bearer ' + token['access_token']
        return token


def push(client, name, tag, label):
    """Push a minimal OCI image through chunked upload endpoints; return manifest digest."""
    config = json.dumps({'architecture': 'arm64', 'os': 'linux', 'rootfs': {'type': 'layers', 'diff_ids': []}, 'config': {'Labels': {'test': label}}}).encode()
    digest = 'sha256:' + hashlib.sha256(config).hexdigest()
    _, headers = client.request(f'/v2/{name}/blobs/uploads/', 'POST', b'', expected=202)
    location = headers['Location']
    assert f'/v2/{name}/blobs/uploads/' in location, location
    _, headers = client.request(location, 'PATCH', config, {'Content-Type': 'application/octet-stream'}, expected=202)
    location = headers['Location']
    client.request(location + ('&' if '?' in location else '?') + 'digest=' + digest, 'PUT', b'', expected=201)
    manifest = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                'config': {'mediaType': 'application/vnd.oci.image.config.v1+json', 'digest': digest, 'size': len(config)}, 'layers': []}
    payload = json.dumps(manifest).encode()
    manifest_digest = 'sha256:' + hashlib.sha256(payload).hexdigest()
    _, headers = client.request(f'/v2/{name}/manifests/{tag}', 'PUT', payload, {'Content-Type': manifest['mediaType']}, expected=201)
    assert headers['Docker-Content-Digest'] == manifest_digest
    downloaded, _ = client.request(f'/v2/{name}/blobs/{digest}')
    assert downloaded == config
    return manifest_digest


def age_fixture(args, sql):
    """Age only this run's repository observations using an explicit fixture database connection."""
    if args.jdbc_url:
        jars = sorted((Path.home() / '.m2/repository/org/postgresql/postgresql').glob('*/postgresql-*.jar'))
        if not jars:
            raise RuntimeError('Build the application first to resolve PostgreSQL JDBC')
        subprocess.run(['java', '--class-path', str(jars[-1]), str(ROOT / 'scripts/FixtureSql.java'), args.jdbc_url], input=sql.encode(), check=True, capture_output=True)
    else:
        subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'grallum', '-d', args.database, '-c', sql], cwd=ROOT, check=True, capture_output=True)


def main():
    """Run assertions against a local deployment, create scoped fixtures and print nonsecret evidence."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://localhost:8080')
    parser.add_argument('--age-fixtures', action='store_true')
    parser.add_argument('--skip-public', action='store_true')
    parser.add_argument('--database', default='grallum', help='Fixture database for --age-fixtures')
    parser.add_argument('--jdbc-url', help='Explicit isolated fixture JDBC URL, alternative to Compose psql')
    parser.add_argument('--docker', action='store_true', help='Also verify real Docker CLI login/pull/push')
    parser.add_argument('--proxy-admin-url', default='http://localhost:5003', help='Local cache control endpoint used only for isolated fixtures')
    args = parser.parse_args()
    password = os.environ.get('ADMIN_PASSWORD') or (ROOT / '.local/admin-password').read_text().strip()
    admin = Client(args.url, os.environ.get('ADMIN_USERNAME', 'admin'), password)
    anon = Client(args.url)
    anon.request('/v2/', expected=401)
    admin.login()
    assert any(u['username'] == admin.username and u['admin'] for u in admin.json('/api/users'))
    admin.request('/')
    docs = admin.json('/v3/api-docs')
    assert '/api/repositories/{repositoryId}/cleanup' in docs['paths']
    admin.request('/swagger-ui/index.html')
    anon.request('/v2/', headers={'Authorization': admin.auth}, expected=401)
    suffix = f'{int(time.time())}-{secrets.token_hex(2)}'
    repo = admin.json('/api/repositories', 'POST', {'registry': 'private', 'name': 'smoke/' + suffix}, expected=201)
    name = repo['registry'] + '/' + repo['name']
    reader_name = 'reader-' + suffix
    reader_password = secrets.token_urlsafe(18)
    admin.json('/api/users', 'POST', {'username': reader_name, 'password': reader_password, 'admin': False}, expected=201)
    reader = Client(args.url, reader_name, reader_password)
    reader.login()
    assert reader.json('/api/repositories')['items'] == []
    reader.request(f'/v2/{name}/tags/list', expected=403)
    admin.json(f"/api/repositories/{repo['id']}/permissions", 'PUT', {'username': reader_name, 'permission': 'READ'})
    reader.registry_tokens.clear()
    assert len(reader.json('/api/repositories')['items']) == 1
    reader.request(f'/v2/{name}/blobs/uploads/', 'POST', b'', expected=403)
    old_digest = push(admin, name, 'old', 'old')
    child_digest = push(admin, name, 'platform-old', 'platform')
    child_payload, _ = admin.request(f'/v2/{name}/manifests/platform-old')
    index = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': [
        {'mediaType': 'application/vnd.oci.image.manifest.v1+json', 'digest': child_digest, 'size': len(child_payload), 'platform': {'architecture': 'arm64', 'os': 'linux'}}]}
    admin.request(f'/v2/{name}/manifests/index-old', 'PUT', json.dumps(index).encode(), {'Content-Type': index['mediaType']}, expected=201)
    new_digest = push(admin, name, 'latest', 'latest')
    reader.request(f'/v2/{name}/manifests/latest')
    reader.request(f'/v2/{name}/manifests/{new_digest}', 'DELETE', expected=403)
    assert len(admin.json(f"/api/repositories/{repo['id']}/tags")["items"]) == 4
    reader.request('/api/users', expected=403)
    reader.request(f"/api/repositories/{repo['id']}/cleanup", 'POST', expected=403)
    policy = {'keepLatest': 1, 'olderThanDays': 1, 'protectedPattern': 'latest', 'enabled': True, 'expectedVersion': 0}
    admin.json(f"/api/repositories/{repo['id']}/policy", 'PUT', policy)
    if args.age_fixtures:
        sql = f"update tag_observation set first_seen=now()-interval '10 days' where repo_id={int(repo['id'])} and tag<>'latest'"
        age_fixture(args, sql)
    preview = admin.json(f"/api/repositories/{repo['id']}/cleanup", 'POST')
    assert preview['deletedDigests'] == []
    if args.age_fixtures:
        assert preview['candidateDigests'] == [old_digest], preview
    admin.request(f'/v2/{name}/manifests/old')
    executed = admin.json(f"/api/repositories/{repo['id']}/cleanup?dryRun=false", 'POST')
    if args.age_fixtures:
        assert executed['deletedDigests'] == [old_digest], executed
        admin.request(f'/v2/{name}/manifests/old', expected=404)
    admin.request(f'/v2/{name}/manifests/latest')
    admin.request(f'/v2/{name}/manifests/platform-old')
    admin.request(f'/v2/{name}/manifests/index-old')
    admin.json(f"/api/repositories/{repo['id']}/permissions", 'PUT', {'username': reader_name, 'permission': 'WRITE'})
    reader.registry_tokens.clear()
    push(reader, name, 'writer', 'writer')
    reader.request(f"/api/repositories/{repo['id']}/policy", 'PUT', policy, expected=403)
    admin.json(f"/api/repositories/{repo['id']}/permissions", 'PUT', {'username': reader_name, 'permission': 'MANAGE'})
    reader.registry_tokens.clear()
    policy["expectedVersion"] = admin.json(f"/api/repositories/{repo['id']}/policy")["version"]
    reader.json(f"/api/repositories/{repo['id']}/policy", 'PUT', policy)
    reader.json(f"/api/repositories/{repo['id']}/cleanup", 'POST')
    reader.request('/api/users', expected=403)
    admin.json(f"/api/repositories/{repo['id']}/permissions/{reader_name}", 'DELETE')
    reader.request(f'/v2/{name}/manifests/latest', expected=403)
    admin.json(f'/api/users/{reader_name}', 'PUT', {'enabled': False, 'admin': False})
    reader.request(f'/v2/{name}/manifests/latest', expected=401)
    print('PASS JWT audience isolation, Swagger, ACL, OCI uploads/downloads, cleanup preview/execution, protected tag and multiarch graph, disabled user')
    if not args.skip_public:
        repos = admin.json('/api/repositories')['items']
        proxy = next((r for r in repos if r['registry'] == 'dockerhub' and r['name'] == 'library/hello-world'), None)
        if proxy is None:
            proxy = admin.json('/api/repositories', 'POST', {'registry': 'dockerhub', 'name': 'library/hello-world'}, expected=201)
        path = '/v2/dockerhub/library/hello-world'
        payload, _ = admin.request(path + '/manifests/latest', headers={'Accept': 'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'})
        index = json.loads(payload)
        digest = index['manifests'][0]['digest']
        payload, _ = admin.request(path + '/manifests/' + digest, headers={'Accept': 'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'})
        manifest = json.loads(payload)
        blob = manifest['layers'][0]['digest']
        data, _ = admin.request(path + '/blobs/' + blob)
        assert 'sha256:' + hashlib.sha256(data).hexdigest() == blob
        admin.request(path + '/blobs/uploads/', 'POST', b'', expected=405)
        print('PASS Docker Hub proxy index, platform manifest, verified layer download, read-only enforcement')
        cached_name = 'smoke/proxy-' + suffix
        cached = admin.json('/api/repositories', 'POST', {'registry': 'dockerhub', 'name': cached_name}, expected=201)
        control = Client(args.proxy_admin_url)
        cached_old = push(control, cached_name, 'old', 'cached-old')
        push(control, cached_name, 'recent', 'cached-recent')
        push(control, cached_name, 'latest', 'cached-latest')
        assert len(admin.json(f"/api/repositories/{cached['id']}/tags")["items"]) == 3
        cached_policy = {'keepLatest': 2, 'olderThanDays': 2, 'protectedPattern': 'latest', 'enabled': True, 'expectedVersion': 0}
        admin.json(f"/api/repositories/{cached['id']}/policy", 'PUT', cached_policy)
        assert admin.json(f"/api/repositories/{repo['id']}/policy")['keepLatest'] == 1
        if args.age_fixtures:
            sql = f"update tag_observation set first_seen=now()-interval '10 days' where repo_id={int(cached['id'])} and tag='old'"
            age_fixture(args, sql)
        preview = admin.json(f"/api/repositories/{cached['id']}/cleanup", 'POST')
        assert preview['deletedDigests'] == []
        control.request(f'/v2/{cached_name}/manifests/old')
        deleted = admin.json(f"/api/repositories/{cached['id']}/cleanup?dryRun=false", 'POST')
        if args.age_fixtures:
            assert preview['candidateDigests'] == [cached_old]
            assert deleted['deletedDigests'] == [cached_old]
            control.request(f'/v2/{cached_name}/manifests/old', expected=404)
        control.request(f'/v2/{cached_name}/manifests/latest')
        admin.request(f'/v2/dockerhub/{cached_name}/blobs/uploads/', 'POST', b'', expected=405)
        print('PASS independent proxy repository policy, cached metadata, cache cleanup preview/deletion and protected tag')
    assert admin.json(f"/api/repositories/{repo['id']}/cleanup/{executed['runId']}")['runId'] == executed['runId']
    if args.docker:
        host = urllib.parse.urlparse(args.url).netloc
        with tempfile.TemporaryDirectory(prefix='grallum-docker-') as config:
            command = ['docker', '--config', config]
            subprocess.run(command + ['login', host, '-u', os.environ.get('ADMIN_USERNAME', 'admin'), '--password-stdin'], input=password.encode(), check=True, capture_output=True)
            image = host + '/' + name + ':latest'
            target = host + '/' + name + ':docker-copy'
            subprocess.run(command + ['pull', image], check=True, capture_output=True)
            subprocess.run(command + ['tag', image, target], check=True, capture_output=True)
            subprocess.run(command + ['push', target], check=True, capture_output=True)
            subprocess.run(command + ['image', 'rm', target], check=True, capture_output=True)
            subprocess.run(command + ['pull', target], check=True, capture_output=True)
            if not args.skip_public:
                subprocess.run(command + ['pull', host + '/dockerhub/library/hello-world:latest'], check=True, capture_output=True)
        print('PASS Docker CLI login, private pull/push/re-pull and public proxy pull')
    print(f"PASS persisted cleanup result; fixture repository: {name}; id={repo['id']}")


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        print((error.stderr or b'').decode(errors='replace'))
        raise
