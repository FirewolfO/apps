#!/usr/bin/env python3
"""Prepare immutable HTTPS media on the development machine; never commit media.

Uses the same PDF extraction as align_transcripts.py and verifies every cue hash.
Keeps the original alignment/audio identity for locally imported WMA files while
adding the fingerprint of the AAC rendition. No timestamps are estimated/shifted.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.parse
import urllib.request

from align_transcripts import COUNTS, transcript, text_digest


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def duration(path):
    return float(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries', 'format=duration',
        '-of', 'default=noprint_wrappers=1:nokey=1', str(path)], text=True).strip())


def prepare(args, season, episode):
    key = f'S{season:02d}E{episode:02d}'
    if key in {'S10E12', 'S10E18'}:
        return None
    source = args.cache / key / 'audio.wma'
    source.parent.mkdir(parents=True, exist_ok=True)
    if not source.is_file():
        if not args.source_url:
            if args.skip_unavailable_audio:
                print(f'{key}: omitted (source audio unavailable)', flush=True)
                return None
            raise ValueError(f'{key}: audio unavailable; supply --source-url for migration')
        segments = ['老友记.Friends.全10季音频', f'老友记.Friends.S{season:02d}', f'老友记.friends.{key}.wma']
        url = args.source_url.rstrip('/') + '/' + '/'.join(urllib.parse.quote(s) for s in segments)
        temporary = source.with_suffix('.part')
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as target:
            shutil.copyfileobj(response, target)
        temporary.replace(source)
    source_hash = sha256(source)
    index_file = args.indexes / (key + '.tsv')
    lines = index_file.read_text().splitlines() if index_file.exists() else []
    if lines and lines[0].split('\t')[2] != source_hash:
        raise ValueError(f'{key}: source audio differs from aligned audio')
    directory = args.output / key
    directory.mkdir(parents=True, exist_ok=True)
    audio = directory / 'audio.m4a'
    receipt = directory / 'rendition.json'
    settings = {'sourceSha256': source_hash, 'codec': 'aac', 'bitrate': '96k'}
    if not audio.is_file() or not receipt.is_file() or json.loads(receipt.read_text()) != settings:
        temporary = directory / 'audio.part.m4a'
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(source),
                        '-map', '0:a:0', '-vn', '-map_metadata', '-1', '-c:a', 'aac', '-b:a', '96k',
                        '-threads', '1', '-movflags', '+faststart', str(temporary)], check=True)
        temporary.replace(audio)
        receipt.write_text(json.dumps(settings) + '\n')
    source_duration, served_duration = duration(source), duration(audio)
    if abs(source_duration - served_duration) >= 0.25:
        raise ValueError(f'{key}: transcoding changed duration: {source_duration} -> {served_duration}')
    audio_hash = sha256(audio)
    paths = [audio]
    if lines:
        pdf = args.cache / key / 'transcript.pdf'
        header = lines[0].split('\t')
        if sha256(pdf) != header[3]:
            raise ValueError(f'{key}: PDF differs from aligned transcript')
        text = transcript(pdf, pdfbox=args.pdfbox, java=args.java)
        if len(text) != len(lines) - 1 or any(text_digest(t) != row.split('\t')[0] for t, row in zip(text, lines[1:])):
            raise ValueError(f'{key}: processed text differs from alignment')
        header = [field for field in header if not field.startswith('remote=')]
        header.append('remote=' + audio_hash)
        index_text = '\t'.join(header) + '\n' + '\n'.join(lines[1:]) + '\n'
        index_file.write_text(index_text)
        (directory / 'alignment.tsv').write_text(index_text)
        (directory / 'transcript.txt').write_text('\f'.join(text), encoding='utf-8')
        paths.extend([directory / 'alignment.tsv', directory / 'transcript.txt'])
    print(f'{key}: {audio.stat().st_size} bytes, duration delta {served_duration-source_duration:.3f}s', flush=True)
    return {'episode': key, 'sourceSha256': source_hash, 'durationMs': round(served_duration * 1000),
            'files': [{'path': str(p.relative_to(args.output)), 'size': p.stat().st_size, 'sha256': sha256(p)} for p in paths]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--indexes', type=Path, required=True)
    parser.add_argument('--pdfbox', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--source-url', help='One-time source for episodes absent from the alignment cache')
    parser.add_argument('--skip-unavailable-audio', action='store_true',
                        help='Publish only existing local audio; list omissions in the manifest')
    parser.add_argument('--workers', type=int, default=8)
    args = parser.parse_args()
    if args.output.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        parser.error('Private media output must be outside the Git repository')
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(prepare, args, season, episode) for season, count in enumerate(COUNTS, 1)
                   for episode in range(1, count + 1)]
        episodes = [result for future in futures if (result := future.result()) is not None]
    available = {e['episode'] for e in episodes}
    missing = [f'S{s:02d}E{e:02d}' for s, count in enumerate(COUNTS, 1) for e in range(1, count + 1)
               if f'S{s:02d}E{e:02d}' not in available]
    manifest = {'version': 1, 'audioCount': len(episodes), 'missingAudio': missing,
                'subtitleCount': sum(len(e['files']) > 1 for e in episodes), 'episodes': episodes}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    checksums = [f"{f['sha256']}  {f['path']}" for e in episodes for f in e['files']]
    (args.output / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
    print(f"Prepared {len(episodes)} audio and {manifest['subtitleCount']} subtitles", flush=True)


if __name__ == '__main__':
    main()
