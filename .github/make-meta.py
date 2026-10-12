"""릴리스마다 quiz-meta.json 을 만든다.
release.json 의 kind: questions(문제만 · 자동 적용) / feature(기능 · 알림 후 적용) / native(앱 새로 설치)
app/native-level 이 올라가면 앱(APK)을 새로 깔아야 하는 업데이트로 본다."""
import json, os, shutil, sys
from datetime import datetime, timedelta, timezone

run = int(sys.argv[1])
rel = json.load(open('release.json', encoding='utf-8'))
kind, notes = rel.get('kind'), (rel.get('notes') or '').strip()
if kind not in ('questions', 'feature', 'native') or not notes:
    sys.exit('release.json 의 kind/notes 를 확인하세요')
native = int(open('app/native-level').read().strip())
prev = {}
if os.path.exists('prev/quiz-meta.json'):
    prev = json.load(open('prev/quiz-meta.json', encoding='utf-8'))
meta = {
    'content': run, 'native': native, 'kind': kind, 'notes': notes,
    'featureSince': prev.get('featureSince', 0), 'featureNotes': prev.get('featureNotes', ''),
    'nativeNotes': prev.get('nativeNotes', ''),
}
if native > prev.get('native', 0):
    meta['nativeNotes'] = notes
    kind = 'native'
if kind in ('feature', 'native'):
    meta['featureSince'] = run
    meta['featureNotes'] = notes
# 패치 노트: 지난 릴리스 기록에 이번 것을 더해 퀴즈 파일에 심음 (버전 확인 창에서 보여 줌)
history = prev.get('history') or json.load(open('changelog-seed.json', encoding='utf-8'))
history = [h for h in history if h.get('v') != run]
today = datetime.now(timezone(timedelta(hours=9))).strftime('%Y-%m-%d')
history.append({'v': run, 'd': today, 'n': notes})
meta['history'] = history
page = 'app/src/main/assets/index.html'
html = open(page, encoding='utf-8').read()
mark = '/*BQBUILD*/null'
if mark not in html:
    sys.exit('퀴즈 파일에 버전 자리(/*BQBUILD*/null)가 없어요')
build = {'v': run, 'name': '1.0.%d' % run, 'history': history[-30:]}
html = html.replace(mark, json.dumps(build, ensure_ascii=False).replace('</', '<\\/'))
open(page, 'w', encoding='utf-8').write(html)
os.makedirs('out', exist_ok=True)
json.dump(meta, open('out/quiz-meta.json', 'w', encoding='utf-8'), ensure_ascii=False)
shutil.copy('app/src/main/assets/index.html', 'out/quiz.html')
open('out/notes.txt', 'w', encoding='utf-8').write(notes + '\n\n휴대폰에서 아래 Assets의 apk 파일을 눌러 받아 설치하세요.\n')
print(json.dumps(meta, ensure_ascii=False))
