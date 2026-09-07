#!/usr/bin/env python3
"""Docker/HTTP boundary for the real deployment entry point's execution tests."""
import json
import os
from pathlib import Path
import re
import signal
import sys
import time

path = Path(os.environ['FAKE_DEPLOY_STATE'])
state = json.loads(path.read_text())
args = sys.argv[1:]
command = Path(sys.argv[0]).name
operation = '-'.join(args[:2]) if args[0] == 'image' else args[0]
state.setdefault('calls', []).append([command, *args])


def save():
    # The parent may inspect state while a surviving command finishes after SIGKILL.
    temporary = path.with_name(path.name + '.' + str(os.getpid()) + '.tmp')
    temporary.write_text(json.dumps(state))
    temporary.replace(path)


def finish(value='', code=0):
    kill_at(operation + '-after')
    save()
    if value:
        print(value)
    sys.exit(code)


def container(ref):
    return next((c for c in state['containers'] if ref in (c['Id'], c['Name'].lstrip('/'))), None)


def supervisor():
    return int(os.environ['FAKE_DEPLOY_SUPERVISOR_PID'])


def kill_at(point):
    if state.get('kill_at') == point and not state.get('killed'):
        state['killed'] = True
        save()
        os.kill(supervisor(), signal.SIGKILL)
        if point.endswith('-before'):
            os._exit(0)


kill_at(operation + '-before')


if command == 'curl':
    if state.get('kill_at') == 'health' and not state.get('killed'):
        state['killed'] = True
        save()
        os.kill(os.getppid(), signal.SIGKILL)
    running = next((c for c in state['containers'] if c['Name'] == '/hof-test' and c['State']['Running']), None)
    if not running:
        finish(code=7)
    old = running['Id'] == 'old-id'
    fault = state.get('fault')
    if not old and fault == 'signal':
        save()
        os.kill(os.getppid(), int(state['signal']))
        time.sleep(10)
    if not old and fault == 'hung-health':
        save()
        time.sleep(10)
    if not old and fault == 'slow-health':
        save()
        time.sleep(0.4)
    if fault == 'public-health' and args[-1].startswith('https:'):
        finish(code=22)
    if fault == 'rollback-health' or (not old and fault in ('internal-health', 'removed-response-lost')):
        finish('{"status":"DOWN","components":{"db":{"status":"UP"}}}')
    finish('{"status":"UP"}')

if args[:2] == ['image', 'inspect']:
    finish(json.dumps([{'Id': 'new-image-id'}]) if state.get('fault') != 'missing-image' else '',
           1 if state.get('fault') == 'missing-image' else 0)
if args[:2] == ['image', 'tag']:
    finish(code=1 if state.get('fault') == 'cleanup' else 0)
if args[:2] == ['container', 'ls']:
    selector = args[args.index('--filter') + 1]
    finish('\n'.join(c['Id'] for c in state['containers']
                     if (c['Id'] == selector[3:] if selector.startswith('id=') else re.search(selector[5:], c['Name']))))
if args[:2] == ['container', 'inspect']:
    c = container(args[-1])
    finish(json.dumps([c]) if c else '', 0 if c else 1)
if args[0] == 'create':
    if state.get('fault') == 'create':
        finish(code=state.get('exit_code', 1))
    mounts = []
    labels = {}
    for i, value in enumerate(args):
        if value == '--mount':
            fields = dict(pair.split('=', 1) for pair in args[i + 1].split(',') if '=' in pair)
            mounts.append({'Source': fields['src'], 'Destination': fields['dst']})
        if value == '--label':
            key, value = args[i + 1].split('=', 1)
            labels[key] = value
    state['create_count'] = state.get('create_count', 0) + 1
    created_id = 'new-id' if state['create_count'] == 1 else 'new-id-' + str(state['create_count'])
    state['containers'].append({'Id': created_id, 'Image': 'new-image-id',
        'Name': '/' + args[args.index('--name') + 1], 'State': {'Running': False},
        'Config': {'Labels': labels, 'Image': args[-1]}, 'Mounts': mounts})
    finish(created_id)
if args[0] in ('stop', 'start', 'rename', 'rm'):
    ref = args[-2] if args[0] == 'rename' else args[-1]
    c = container(ref)
    if not c:
        finish(code=1)
    if args[0] == 'rename':
        if c['Id'] == 'old-id' and state.get('fault') == 'rename' and not state.get('rename_failed'):
            state['rename_failed'] = True
            finish(code=1)
        if container(args[-1]):
            finish(code=1)
        c['Name'] = '/' + args[-1]
    elif args[0] == 'start':
        if c['Id'] == 'new-id' and state.get('fault') == 'start':
            finish(code=1)
        if any(other['State']['Running'] for other in state['containers'] if other['Id'] != c['Id']):
            finish('overlapping backend processes', 1)
        c['State']['Running'] = True
    elif args[0] == 'stop':
        c['State']['Running'] = False
        if state.get('fault') == 'signal-stop':
            save()
            os.kill(supervisor(), int(state['signal']))
            time.sleep(0.2)
        if state.get('fault') == 'hung-stop' and not state.get('stop_failed'):
            state['stop_failed'] = True
            save()
            time.sleep(1.3)
    else:
        state['containers'].remove(c)
        if c['Id'] == 'new-id' and state.get('fault') == 'removed-response-lost':
            save()
            time.sleep(10)
    finish(c['Id'] if args[0] in ('stop', 'start') else '')
finish('Unsupported fake boundary command: ' + repr(args), 99)
