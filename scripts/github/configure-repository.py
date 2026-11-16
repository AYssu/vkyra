#!/usr/bin/env python3
"""Configure review and CI protection after the first successful remote CI run.

Requires authenticated gh and repository admin access. Preview with --dry-run.
Existing checks/reviewer counts/restrictions are preserved or strengthened.
Administrator bypass is disabled unless explicitly requested.
"""
import argparse
import json
import re
import subprocess
import urllib.parse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('repository', help='OWNER/REPOSITORY')
parser.add_argument('--branch', default='main')
parser.add_argument('--dry-run', action='store_true')
parser.add_argument('--allow-admin-bypass', action='store_true', help='Explicitly allow repository administrators to bypass main protection (single-maintainer repositories).')
parser.add_argument('--require-code-owner-reviews', action='store_true', help='Require approval from the actual maintainers listed in .github/CODEOWNERS.')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
    parser.error('Use OWNER/REPOSITORY')
if not args.branch or any(value in args.branch for value in ('..', '\n', '\r', '?', '#')):
    parser.error('Invalid branch')
repo_path = f'repos/{args.repository}'
branch_path = f'{repo_path}/branches/{urllib.parse.quote(args.branch, safe="")}'

def api(path, method='GET', body=None, optional_404=False):
    command = ['gh', 'api', '--method', method, path]
    if body is not None:
        command.extend(['--input', '-'])
    result = subprocess.run(command, input=json.dumps(body) if body is not None else None, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        if optional_404 and 'HTTP 404' in result.stderr:
            return None
        raise SystemExit(f'GitHub API failed for {path}: {result.stderr.strip()}')
    return json.loads(result.stdout) if result.stdout.strip() else None

repo = api(repo_path)
if not repo.get('permissions', {}).get('admin'):
    raise SystemExit('Repository admin access is required; authenticate with gh auth login first.')
branch = api(branch_path)
check_runs = api(f'{repo_path}/commits/{branch["commit"]["sha"]}/check-runs?per_page=100')['check_runs']
checks = [item for item in check_runs if item['name'] == 'CI required' and item['app']['slug'] == 'github-actions']
if not checks or checks[0].get('conclusion') != 'success':
    raise SystemExit('Run CI on the current branch commit first; its GitHub Actions check "CI required" must succeed before protection is configured.')
previous = api(branch_path + '/protection', optional_404=True) or {}
status = previous.get('required_status_checks') or {}
required_checks = {item['context']: {'context': item['context'], 'app_id': (item.get('app_id') or -1)} for item in status.get('checks', [])}
for context in status.get('contexts', []):
    required_checks.setdefault(context, {'context': context, 'app_id': -1})
required_checks['CI required'] = {'context': 'CI required', 'app_id': checks[0]['app']['id']}
review = previous.get('required_pull_request_reviews') or {}
reviews = {
    'dismiss_stale_reviews': True,
    'require_code_owner_reviews': args.require_code_owner_reviews or review.get('require_code_owner_reviews', False),
    'required_approving_review_count': max(1, review.get('required_approving_review_count', 0)),
    'require_last_push_approval': review.get('require_last_push_approval', False),
}
if review.get('dismissal_restrictions'):
    restrictions = review['dismissal_restrictions']
    reviews['dismissal_restrictions'] = {'users': [item['login'] for item in restrictions.get('users', [])], 'teams': [item['slug'] for item in restrictions.get('teams', [])]}
restrictions = previous.get('restrictions')
if restrictions is not None:
    restrictions = {'users': [item['login'] for item in restrictions.get('users', [])],
                    'teams': [item['slug'] for item in restrictions.get('teams', [])],
                    'apps': [item['slug'] for item in restrictions.get('apps', [])]}
payload = {
    'required_status_checks': {'strict': True, 'checks': list(required_checks.values())},
    'enforce_admins': not args.allow_admin_bypass,
    'required_pull_request_reviews': reviews,
    'restrictions': restrictions,
    'required_conversation_resolution': True,
    'allow_force_pushes': False,
    'allow_deletions': False,
    'block_creations': (previous.get('block_creations') or {}).get('enabled', False),
    'lock_branch': (previous.get('lock_branch') or {}).get('enabled', False),
    'required_linear_history': (previous.get('required_linear_history') or {}).get('enabled', False),
    'allow_fork_syncing': (previous.get('allow_fork_syncing') or {}).get('enabled', False),
}
print(json.dumps({'repositorySettings': {'has_issues': True, 'delete_branch_on_merge': True}, 'branchProtection': payload}, indent=2))
if args.dry_run:
    print('Preview only; no remote settings changed.')
else:
    api(branch_path + '/protection', method='PUT', body=payload)
    api(repo_path, method='PATCH', body={'has_issues': True, 'delete_branch_on_merge': True})
    scope = 'administrators may bypass by explicit request' if args.allow_admin_bypass else 'administrators must also comply'
    print(f'{args.repository}:{args.branch} now requires an up-to-date passing CI and PR review; {scope}.')
