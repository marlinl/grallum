#!/usr/bin/env python3
"""Prepare and check isolated cleanup intents across restarts and runtime scheduler switches."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid
from smoke import Client, push, ROOT


def sql(args, statement):
    """Execute fixture-only SQL on the explicitly supplied test database, returning result text."""
    jars = sorted((Path.home() / '.m2/repository/org/postgresql/postgresql').glob('*/postgresql-*.jar'))
    result = subprocess.run(['java', '--class-path', str(jars[-1]), str(ROOT / 'scripts/FixtureSql.java'), args.jdbc_url], input=statement, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def main():
    """Create owned fixtures or verify recovery without deleting unrelated repositories or metadata."""
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://localhost:8080')
    parser.add_argument('--jdbc-url', required=True, help='Isolated PostgreSQL fixture database only')
    parser.add_argument('--state-file', default='.local/recovery-fixture.json')
    parser.add_argument('action', choices=['prepare', 'check-disabled', 'check-enabled'])
    args = parser.parse_args()
    path = Path(args.state_file)
    password = os.environ.get('ADMIN_PASSWORD') or (ROOT / '.local/admin-password').read_text().strip()
    admin = Client(args.url, os.environ.get('ADMIN_USERNAME', 'admin'), password)
    admin.login()
    if args.action == 'prepare':
        repo = admin.json('/api/repositories', 'POST', {'registry': 'private', 'name': 'recovery/' + secrets.token_hex(8)}, expected=201)
        name = 'private/' + repo['name']
        present = push(admin, name, 'latest', 'recovery-present')
        planned = push(admin, name, 'planned', 'recovery-planned')
        absent = 'sha256:' + hashlib.sha256(secrets.token_bytes(32)).hexdigest()
        runs = [str(uuid.uuid4()), str(uuid.uuid4())]
        admin.json(f"/api/repositories/{repo['id']}/policy", 'PUT', {'keepLatest': 2, 'olderThanDays': 36500, 'protectedPattern': '*', 'enabled': True, 'expectedVersion': 0})
        for run in runs:
            sql(args, f"insert into docker_cleanup_run(id,repository_id,actor,dry_run,policy_version,policy_snapshot,status,started_at) values('{run}',{int(repo['id'])},'recovery-fixture',false,1,'{{}}','RUNNING',now())")
        sql(args, f"insert into docker_cleanup_item(run_id,digest,status) values('{runs[0]}','{present}','INTENT'),('{runs[0]}','{absent}','INTENT'),('{runs[1]}','{planned}','PLANNED')")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({'repositoryId': repo['id'], 'name': name, 'present': present, 'planned': planned, 'absent': absent, 'runs': runs}))
        print('PASS prepared isolated incomplete intents; restart the test instance before checking')
        return
    fixture = json.loads(path.read_text())
    run, untouched = fixture['runs']
    if args.action == 'check-disabled':
        time.sleep(3)
        assert sql(args, f"select status from docker_cleanup_run where id='{run}'") == 'RUNNING'
        assert sql(args, f"select count(*) from docker_cleanup_run where repository_id={int(fixture['repositoryId'])} and actor='scheduler'") == '0'
        print('PASS runtime scheduling switch disables both cron and recovery')
        return
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        status = sql(args, f"select status from docker_cleanup_run where id='{run}'")
        scheduled = sql(args, f"select count(*) from docker_cleanup_run where repository_id={int(fixture['repositoryId'])} and actor='scheduler' and status='SUCCEEDED'")
        if status == 'PARTIAL' and int(scheduled) > 0:
            break
        time.sleep(.5)
    assert status == 'PARTIAL', status
    assert int(scheduled) > 0, 'cron did not produce a successful task'
    items = sql(args, f"select digest,status from docker_cleanup_item where run_id='{run}'")
    assert fixture['present'] + '\tUNCERTAIN' in items
    assert fixture['absent'] + '\tALREADY_ABSENT' in items
    assert sql(args, f"select status from docker_cleanup_run where id='{untouched}'") == 'FAILED'
    assert sql(args, f"select status from docker_cleanup_item where run_id='{untouched}'") == 'PLANNED'
    admin.request('/v2/' + fixture['name'] + '/manifests/latest')
    admin.request('/v2/' + fixture['name'] + '/manifests/planned')
    result = admin.json(f"/api/repositories/{fixture['repositoryId']}/cleanup/{run}")
    assert result['status'] == 'PARTIAL' and fixture['absent'] in result['deletedDigests']
    print('PASS cron execution, restart recovery, confirmed absence, uncertain existing content and untouched candidates')


if __name__ == '__main__':
    main()
