#!/usr/bin/env python3
"""Configure the optional Unsplash wallpaper provider without exposing keys in argv or logs."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request


def read_access_key(path):
    for line in Path(path).expanduser().read_text().splitlines():
        line = line.strip().removeprefix('export ').strip()
        name, separator, value = line.partition('=')
        if separator and name.strip() == 'UNSPLASH_ACCESS_KEY':
            value = value.strip()
            if value.startswith(('"', "'")):
                quote = value[0]
                end = value.find(quote, 1)
                if end < 0:
                    raise ValueError('Unclosed Access Key quote')
                value = value[1:end]
            else:
                value = value.split(' #', 1)[0].strip()
            if not re.fullmatch(r'[A-Za-z0-9_-]{10,200}', value):
                raise ValueError('Invalid Access Key format')
            return value
    raise ValueError('UNSPLASH_ACCESS_KEY is missing')


def staging_command():
    path = '/sdcard/Android/data/com.gathin.tvhome/files/unsplash-setup.json'
    return ' && '.join([
        'mkdir -p /sdcard/Android/data/com.gathin.tvhome/files',
        'umask 077',
        'cat > ' + path,
        'chmod 644 ' + path,
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', required=True)
    parser.add_argument('--collections', required=True,
                        help='Comma-separated collection IDs, without URL slugs')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+(,[A-Za-z0-9_-]+)*', args.collections):
        parser.error('Use comma-separated collection IDs')
    try:
        key = read_access_key(args.env_file)
        # Validate every collection, rather than silently ignoring inaccessible ones.
        for collection in args.collections.split(','):
            request = urllib.request.Request(
                'https://api.unsplash.com/collections/' + collection,
                headers={'Authorization': 'Client-ID ' + key, 'Accept-Version': 'v1'})
            try:
                with urllib.request.urlopen(request, timeout=20) as response:
                    data = json.load(response)
                    if not data.get('total_photos', 0):
                        raise ValueError('Collection ' + collection + ' is empty')
                print('Collection ' + collection + ': accessible (' + str(data['total_photos']) + ' photos)')
            except urllib.error.HTTPError as error:
                raise ValueError('Collection ' + collection + ': API HTTP ' + str(error.code)) from None
        if args.check_only:
            return
        setup = json.dumps({'access_key': key, 'collections': args.collections}).encode()
        subprocess.run([args.adb, '-s', args.serial, 'shell', staging_command()],
                       input=setup, check=True, stdout=subprocess.DEVNULL)
        print('Setup transferred. Restart the updated launcher to import it into private storage.')
    except (OSError, ValueError, urllib.error.URLError, subprocess.CalledProcessError) as error:
        # Network exception text may contain URLs; only emit deliberately constructed validation messages.
        print(str(error) if isinstance(error, ValueError) else 'Setup failed; check network and ADB connection.',
              file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
