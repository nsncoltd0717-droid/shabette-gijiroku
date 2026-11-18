"""src/app.html（claude.ai公開版と同じ本文）から、単体で動く web/index.html を作る。"""
import pathlib
root = pathlib.Path(__file__).resolve().parent.parent
s = (root / 'src/app.html').read_text(encoding='utf-8')
head = '''<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#2a1f5c">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="議事録">
<link rel="apple-touch-icon" href="icon-180.png">
<link rel="icon" href="icon-180.png">
<link rel="manifest" href="manifest.webmanifest">
'''
i = s.index('</style>') + len('</style>')
(root / 'web/index.html').write_text(head + s[:i] + '\n</head>\n<body>\n' + s[i:] + '\n</body>\n</html>\n', encoding='utf-8')
print('web/index.html を更新しました')
