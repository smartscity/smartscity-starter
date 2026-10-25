#!/usr/bin/env python3
"""Render Smartscity measurements without launching Java or modifying raw results."""
import argparse
from html import escape
import json
import math
from pathlib import Path


IMPLEMENTATIONS = ('smartscity',)
PROFILES = ('standard', 'high-throughput')
THREADS = (1, 4, 8)
LABELS = {'smartscity': 'Smartscity'}


def number(value, digits=0):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return 'NOT_RECORDED'
    return f'{value:,.{digits}f}'


def markdown_table(headers, rows):
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')
    cells = [[cell(v) for v in row] for row in [headers] + rows]
    widths = [max(len(row[i]) for row in cells) for i in range(len(headers))]
    lines = ['| ' + ' | '.join(v.ljust(widths[i]) for i, v in enumerate(row)) + ' |'
             for row in cells]
    lines.insert(1, '| ' + ' | '.join('-' * width for width in widths) + ' |')
    return '\n'.join(lines)


def write_report(directory):
    """Write derived HTML/Markdown views; preserve measured, partial and drift states verbatim."""
    directory = Path(directory)
    result = json.loads((directory / 'result.json').read_text())
    environment_path = directory / 'environment.json'
    environment = json.loads(environment_path.read_text()) if environment_path.exists() else {}
    cases = [row for row in result.get('jmh', []) if row['implementation'] == 'smartscity']
    index = {}
    duplicates = []
    for row in cases:
        key = (row['profile'], row['threads'], row['implementation'])
        if key in index:
            duplicates.append(str(key))
        index[key] = row
    expected = {(p, t, i) for p in PROFILES for t in THREADS for i in IMPLEMENTATIONS}
    status = result.get('status', 'UNKNOWN')
    source_state = {True: 'UNCHANGED', False: 'CHANGED'}.get(result.get('sourceUnchanged'), 'UNKNOWN')
    complete = (status == 'MEASURED' and source_state == 'UNCHANGED'
                  and not duplicates and not result.get('errors') and expected == set(index))
    sections = []

    def heading(title):
        sections.append(('heading', title))

    def paragraph(text):
        sections.append(('paragraph', text))

    def table(headers, rows):
        sections.append(('table', (headers, rows)))

    heading('Run status')
    paragraph(f'Saved run: {directory.name}. This view contains only Smartscity measurements.')
    paragraph(f'Recorded run status: {status}. Completed Smartscity scenarios: '
              f'{len(expected & set(index))}/{len(expected)}. Source fingerprint: {source_state}.')
    if not complete:
        paragraph('INCOMPLETE / NOT_VERIFIED: inspect the recorded errors and source state. '
                  'This view does not upgrade the raw run status.')
    for message in result.get('errors', []):
        paragraph('Recorded error: ' + str(message))
    if duplicates:
        paragraph('Duplicate scenarios: ' + ', '.join(duplicates))
    before_path, after_path = directory / 'sources-before.json', directory / 'sources-after.json'
    if before_path.exists() and after_path.exists():
        before, after = json.loads(before_path.read_text()), json.loads(after_path.read_text())
        changed = [key for key in sorted(before.keys() | after.keys()) if before.get(key) != after.get(key)]
        if changed:
            table(['Changed input during measurement'], [[key] for key in changed])

    summary_headers = ['Profile', 'Threads', 'Smartscity IDs/s']
    summary = []
    for profile in PROFILES:
        for threads in THREADS:
            row = index.get((profile, threads, 'smartscity'))
            summary.append([profile, str(threads),
                            number(row.get('idsPerSecond')) if row else 'NOT_RUN'])

    heading('Measurement details')
    paragraph('The +/- column is JMH\'s 99.9% confidence-interval half-width for successful IDs/s, '
              'not request latency. Timeout and rejection columns are failed calls per second. '
              'B/success is estimated '
              'allocation per successful ID, including producer allocation; smaller is better. '
              'Primary JMH operations count attempts, not necessarily successful IDs.')
    detail = []
    for profile in PROFILES:
        for threads in THREADS:
            for impl in IMPLEMENTATIONS:
                row = index.get((profile, threads, impl))
                if row is None:
                    continue
                detail.append([profile, str(threads), LABELS[impl],
                               number(row.get('idsPerSecond')), number(row.get('idsScoreError999')),
                               number(row.get('timeoutsPerSecond'), 3), number(row.get('rejectionsPerSecond'), 3),
                               number(row.get('estimatedBytesPerSuccess'), 6),
                               number(row.get('allocationMiBPerSecond'), 3)])
    table(['Profile', 'Threads', 'Implementation', 'IDs/s', '+/- IDs/s', 'Timeout/s',
           'Rejection/s', 'B/success', 'Allocation MiB/s'], detail)

    heading('Correctness probes (separate from JMH throughput)')
    paragraph('These bounded, process-local probes check duplicate IDs and future timestamp lead. '
              'They do not prove distributed uniqueness or sustained latency. Missing probes are NOT_RUN.')
    probes = {row['implementation']: row for row in result.get('probes', [])}
    probe_rows = []
    for impl in IMPLEMENTATIONS:
        probe = probes.get(impl, {})
        records = probe.get('data') or [{}]
        for row in records:
            probe_rows.append([LABELS[impl], probe.get('status', 'NOT_RUN'),
                               *[number(row.get(key)) for key in
                                 ('attempts', 'ids', 'failures', 'duplicates', 'maxFutureSeconds')]])
    table(['Implementation', 'Status', 'Attempts', 'IDs', 'Failures', 'Duplicates', 'Max future seconds'], probe_rows)

    heading('Environment and reproducibility')
    jmh = environment.get('jmh', {})
    table(['Setting', 'Recorded value'], [
        ['CPU', environment.get('machdep.cpu.brand_string', 'NOT_RECORDED')],
        ['Logical CPUs', number(environment.get('logicalCpus'))],
        ['OS', environment.get('os', 'NOT_RECORDED')],
        ['Java', environment.get('java', 'NOT_RECORDED')],
        ['JVM options', ' '.join(environment.get('jvmOptions', [])) or 'NOT_RECORDED'],
        ['Forks', number(jmh.get('forks'))], ['Warmup', jmh.get('warmup', 'NOT_RECORDED')],
        ['Measurement', jmh.get('measurement', 'NOT_RECORDED')],
        ['Profiler', jmh.get('profiler', 'NOT_RECORDED')],
        ['Measured JAR SHA-256', environment.get('jarSha256', 'NOT_RECORDED')]])
    paragraph('Layouts: standard 30/18/15 bits with 65,536 cache slots; high-throughput '
              '30/16/17 bits with 262,144 slots; epoch 2026-01-01 UTC and worker 1. Cached IDs '
              'can borrow future logical seconds. This is a short local '
              'microbenchmark, not production capacity or a comparison against an earlier run.')
    paragraph('This report is derived from saved measurements; rendering it runs no benchmark. '
              'The source fingerprint describes the measurement interval, not subsequent edits.')

    markdown = ['# Smartscity ID benchmark', '']
    html = ['<!doctype html><html lang="en"><meta charset="utf-8">',
            '<meta name="viewport" content="width=device-width, initial-scale=1">',
            '<title>Smartscity ID benchmark</title><style>',
            'body{margin:0;background:#f3f5f8;color:#17243b;font:15px/1.6 system-ui,sans-serif}',
            'main{max-width:1280px;margin:32px auto;padding:32px;background:white;border-radius:16px}',
            'h1{font-size:32px;margin-top:0}h2{margin-top:36px;border-bottom:2px solid #dce4ef;padding-bottom:8px}',
            'p{max-width:1050px} .table{overflow-x:auto}table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}',
            'th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #dce4ef;white-space:pre-wrap}',
            'th{background:#edf2f9;font-size:13px}tr:nth-child(even){background:#f8fafc}',
            'td{overflow-wrap:anywhere} .status{padding:12px;border-left:5px solid #c17a12;background:#fff5dd}',
            '</style><main><h1>Smartscity ID benchmark</h1>',
            f'<p class="status">{escape(status)} · {len(expected & set(index))}/{len(expected)} Smartscity scenarios</p>']
    for kind, content in sections:
        if kind == 'heading':
            markdown += ['## ' + content, '']
            html.append('<h2>' + escape(content) + '</h2>')
        elif kind == 'paragraph':
            markdown += [content, '']
            html.append('<p>' + escape(content) + '</p>')
        else:
            headers, rows = content
            markdown += [markdown_table(headers, rows), '']
            html.append('<div class="table"><table><thead><tr>' +
                        ''.join('<th>' + escape(str(v)) + '</th>' for v in headers) + '</tr></thead><tbody>')
            for row in rows:
                html.append('<tr>' + ''.join('<td>' + escape(str(v)) + '</td>' for v in row) + '</tr>')
            html.append('</tbody></table></div>')
    html.append('</main></html>')
    (directory / 'benchmark-report.md').write_text('\n'.join(markdown), encoding='utf-8')
    (directory / 'benchmark-report.html').write_text('\n'.join(html), encoding='utf-8')
    return (f'\nSmartscity benchmark: {status}; {len(expected & set(index))}/{len(expected)} scenarios; source {source_state}\n'
            + markdown_table(summary_headers, summary)
            + f'\n\nHTML report: {directory / "benchmark-report.html"}'
            + f'\nMarkdown report: {directory / "benchmark-report.md"}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path, help='Directory containing result.json')
    args = parser.parse_args()
    print(write_report(args.input.resolve()))


if __name__ == '__main__':
    main()
