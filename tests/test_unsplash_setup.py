"""Credential parsing must never evaluate the contents of a shared environment file."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('unsplash_setup', Path(__file__).parents[1] / 'tools/unsplash-setup.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class AccessKeyTests(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'credentials'
            path.write_text(text)
            return setup.read_access_key(path)

    def test_reads_only_access_key_and_supports_quotes(self):
        self.assertEqual('example_access_key', self.parse(
            'UNSPLASH_SECRET_KEY=unused\nexport UNSPLASH_ACCESS_KEY="example_access_key" # comment\n'))

    def test_rejects_shell_expansion_and_missing_key(self):
        for value in ('$(touch /tmp/should-not-exist)', '${OTHER_KEY}', 'short', 'abc\rdefghijk'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse('UNSPLASH_ACCESS_KEY=' + value)
        with self.assertRaises(ValueError):
            self.parse('UNSPLASH_SECRET_KEY=example_secret_key')

    def test_supports_unquoted_comment(self):
        self.assertEqual('example_access_key', self.parse('UNSPLASH_ACCESS_KEY=example_access_key # comment'))
