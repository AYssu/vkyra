"""A Git checkout must preserve the upstream license document digests."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class LicenseCheckoutTest(unittest.TestCase):
    def test_upstream_bytes_survive_git_checkout_with_either_newline_setting(self):
        documents = json.loads((ROOT / 'licenses/SOURCES.json').read_text())['documents']
        for autocrlf in ('false', 'true'):
            with self.subTest(autocrlf=autocrlf), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                repository = root / 'repository'
                repository.mkdir()
                shutil.copyfile(ROOT / '.gitattributes', repository / '.gitattributes')
                for document in documents:
                    target = repository / document['path']
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / document['path'], target)

                def git(*args):
                    return subprocess.run(
                        ['git', '-C', str(repository), '-c', f'core.autocrlf={autocrlf}', *args],
                        capture_output=True, text=True, check=True, timeout=10,
                    )

                git('init', '--quiet')
                git('add', '--all')
                checkout = root / 'checkout'
                checkout.mkdir()
                git('checkout-index', '--all', f'--prefix={checkout}/')
                for document in documents:
                    digest = hashlib.sha256((checkout / document['path']).read_bytes()).hexdigest()
                    self.assertEqual(document['sha256'], digest, document['path'])


if __name__ == '__main__':
    unittest.main()
