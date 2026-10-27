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
    # 示例中的解释注释可以翻译，但示例代码词元、字符串和标识符必须保持一致。
    old_pre = [m for c in old_comments for m in re.findall(r'<pre>.*?</pre>', c, re.S)]
    new_pre = [m for c in new_comments for m in re.findall(r'<pre>.*?</pre>', c, re.S)]
    assert len(old_pre) == len(new_pre), f'Javadoc example count change: {path}'
    for before_pre, after_pre in zip(old_pre, new_pre):
        if before_pre == after_pre:
            continue
        # 移除外层 Javadoc 每行装饰符，保留示例本身；不解码 HTML，不改字面量。
        before_source = re.sub(r'(?m)^\s*\* ?', '', before_pre)
        after_source = re.sub(r'(?m)^\s*\* ?', '', after_pre)
        assert scan_java(before_source)[0] == scan_java(after_source)[0], f'Javadoc example code/string change: {path}'
    # pre 已单独校验，排除其包裹的 {@code ...}，避免把示例注释误判为符号变化。
    old_docs = [re.sub(r'<pre>.*?</pre>', '', c, flags=re.S) for c in old_comments]
    new_docs = [re.sub(r'<pre>.*?</pre>', '', c, flags=re.S) for c in new_comments]
    for pattern in (r'\{@(?:code|link|linkplain|literal|value)\s+[^}]*\}', r'@(?:param|throws|exception)\s+[^\s]+'):
        before = protected_parts(old_docs, pattern)
        after = protected_parts(new_docs, pattern)
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
