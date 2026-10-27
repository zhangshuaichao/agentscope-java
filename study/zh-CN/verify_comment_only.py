#!/usr/bin/env python3
"""验证中文学习分支仅改 Java 注释；不是编译器或运行时测试的替代品。"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = 'a543f993cce6e22fb37c31ae14443cf9104dbeae'

def scan_java(source):
    """按词法边界读取注释与代码词元，保留字符串、字符和文本块原文。"""
    tokens, comments = [], []
    i = 0
    while i < len(source):
        if source[i].isspace():
            i += 1
        elif source.startswith('//', i):
            end = source.find('\n', i)
            end = len(source) if end < 0 else end
            comments.append(source[i:end])
            i = end
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            if end < 0:
                raise ValueError('Unterminated block comment')
            end += 2
            comments.append(source[i:end])
            i = end
        elif source.startswith('"""', i) or source[i] in ('"', "'"):
            start = i
            delimiter = '"""' if source.startswith('"""', i) else source[i]
            i += len(delimiter)
            while i < len(source):
                if source[i] == '\\':
                    i += 2
                elif source.startswith(delimiter, i):
                    i += len(delimiter)
                    break
                else:
                    i += 1
            else:
                raise ValueError('Unterminated string/character/text block')
            tokens.append(source[start:i])
        else:
            match = re.match(r'[\w$]+|>>>=|>>>|>>=|<<=|>>|<<|\+\+|--|&&|\|\||==|!=|<=|>=|\+=|-=|\*=|/=|%=|&=|\|=|\^=|->|::|\.\.\.|\S', source[i:])
            if not match:
                raise ValueError('Cannot tokenize Java source')
            tokens.append(match.group())
            i += len(match.group())
    return tokens, comments

def normalize_tag(text):
    return re.sub(r'\s+', ' ', re.sub(r'\n\s*\* ?', ' ', text)).strip()

def protected_parts(comments, pattern):
    return Counter(normalize_tag(match) for comment in comments for match in re.findall(pattern, comment, re.S))

def verify(path, base):
    old = subprocess.check_output(['git', 'show', f'{base}:{path}'], cwd=ROOT, text=True)
    new = (ROOT / path).read_text()
    old_tokens, old_comments = scan_java(old)
    new_tokens, new_comments = scan_java(new)
    assert old_tokens == new_tokens, f'Executable token/string change: {path}'
    old_licenses = [c for c in old_comments if 'Licensed under the Apache License' in c]
    new_licenses = [c for c in new_comments if 'Licensed under the Apache License' in c]
    assert old_licenses == new_licenses, f'License change: {path}'
    # 保留代码示例的精确字节，包括示例内部原有英文注释。
    old_pre = [m for c in old_comments for m in re.findall(r'<pre>.*?</pre>', c, re.S)]
    new_pre = [m for c in new_comments for m in re.findall(r'<pre>.*?</pre>', c, re.S)]
    assert old_pre == new_pre, f'Javadoc example change: {path}'
    for pattern in (r'\{@(?:code|link|linkplain|literal|value)\s+[^}]*\}', r'@(?:param|throws|exception)\s+[^\s]+'):
        before = protected_parts(old_comments, pattern)
        after = protected_parts(new_comments, pattern)
        assert not before - after, f'Missing/changed Javadoc symbol/tag: {path}'
    # Java 会在词法分析前解释 Unicode 转义，新增此类转义不能当作普通注释放行。
    before_escapes = protected_parts(old_comments, r'\\u+[0-9a-fA-F]{4}')
    after_escapes = protected_parts(new_comments, r'\\u+[0-9a-fA-F]{4}')
    assert before_escapes == after_escapes, f'Unicode escape change in comments: {path}'
    return {'path': path, 'original_comments': len(old_comments), 'current_comments': len(new_comments), 'code_tokens': len(new_tokens), 'learning_notes': new.count('【学习提示】')}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=BASELINE)
    args = parser.parse_args()
    changed = subprocess.check_output(['git', 'diff', '--name-only', args.base, '--', '*.java'], cwd=ROOT, text=True).splitlines()
    files = [verify(path, args.base) for path in changed]
    print(json.dumps({'baseline': args.base, 'checked_files': len(files), 'result': 'PASS', 'files': files}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
