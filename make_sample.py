from pathlib import Path
from html import escape
import zipfile
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent
assets = root / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
cover = Image.new('RGB', (600, 900), '#2c6155')
d = ImageDraw.Draw(cover)
d.rounded_rectangle((32, 32, 568, 868), radius=220, outline='#b8c7b3', width=2)
serif = ImageFont.truetype('C:/Windows/Fonts/georgia.ttf', 64)
small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 22)
d.text((300, 140), 'F O L I O', font=small, anchor='mm', fill='#e4d6b6')
for y, text in [(310, 'THE'), (400, 'QUIET'), (490, 'HOUR')]:
    d.text((300, y), text, font=serif, anchor='mm', fill='#f8f6f1')
d.line((240, 615, 360, 615), fill='#d6ae72', width=3)
d.text((300, 705), 'A SAMPLE BOOK', font=small, anchor='mm', fill='#e4d6b6')
cover.save(assets / 'sample-cover.png')

chapters = [
    ('Welcome', [
        'A cup of tea and a quiet page. That is all you need to begin.',
        'Welcome to Folio. This small book lets you explore the reader before bringing in your own library. It is an ordinary EPUB, opened by the same reading engine as every other book.',
        'Tap the left or right edge of the page to move through the book. You can also swipe, or use the previous and next buttons below. Tap the centre to hide the controls and give the words a little more space.',
        'Open Aa to change the text size, typeface, line spacing and margins. Paper, Sepia and Night offer three ways to make the page comfortable. If you would rather scroll, switch on scrolling there.',
        'Publisher layout preserves a book’s original typography. Turn it off when you want full control of the typeface and spacing. Illustrations and chapter divisions remain part of the book.',
        'The contents button takes you to chapters, bookmarks and notes. Save a bookmark with the bookmark button. Long-press a passage to select it, then choose Highlight or Add note.',
        'The search button finds words throughout the book. A result takes you straight to its passage. Reading progress is saved as you turn pages, so you can leave the book and return later.',
        'The next chapter is a small original story. The final chapter contains Danish text, including æ, ø and å. It is useful for checking how your chosen typeface handles those letters.',
        'All of these sample texts and the cover were created for this app. Your own books are imported separately, and their files stay on your device.',
    ]),
    ('The quiet hour', [
        'The rain stopped at four. Ingrid knew because the roof went quiet before the street did. Water still ran through the gutter, but the soft, steady tapping above her head had ended.',
        'She put down the book with one finger between its pages. Outside, the last customer was wrestling an umbrella into its sleeve. He looked up at the shop window and gave a small, embarrassed wave.',
        'Ingrid waved back. She had sold him a book about gardens, although he had come in looking for a map. The map was unavailable. The gardens, he had decided after a long conversation, would do.',
        'There was an hour until closing. She could begin the accounts. She could unpack the box waiting beneath the counter. Instead, she moved the chair towards the window and returned to her page.',
        'The chair had belonged to her father. It was too low for the counter and too worn for the sitting room upstairs. In the window of the bookshop it was exactly right. People passing sometimes assumed it was for sale.',
        'A bell rang at the door. Ingrid stood, keeping the finger in her book. A girl in a yellow coat entered and carefully wiped her boots twice on the mat.',
        '“I am looking for a book,” the girl said.',
        '“You have come to a sensible place, then.”',
        'The girl smiled, but did not move towards the shelves. She held a folded piece of paper in both hands. One corner had grown soft in the rain.',
        '“I do not remember its name.”',
        'Ingrid set her own book face down on the counter. “Tell me something you remember.”',
        'There had been a house near the sea. A brother and a sister. A tree that leaned towards the kitchen window. Perhaps the tree was only in the picture. Perhaps the sea was a lake.',
        'Ingrid listened. She had learned that a remembered book was rarely made of titles and authors. It was made of afternoons, voices and the shape of a room in which someone had read.',
        'They tried three shelves. They tried a fourth. The girl unfolded her paper, but it contained only a list she had written herself: house, tree, blue, summer. Ingrid found nothing that matched all four.',
        'At the end of the last aisle the girl stopped. A narrow book with a grey cover sat between two much larger ones. She drew it out as gently as if it were asleep.',
        '“This is not it,” she said. “But my grandmother had this one too.”',
        'They opened it together. The first picture showed a kitchen table and two empty cups. The girl touched the page, then took her hand away.',
        '“Would you like to sit down?” Ingrid asked.',
        'The girl sat in the old chair. Ingrid fetched tea from upstairs and put a cup on the little table beside the window. The accounts could wait. The box beneath the counter could wait as well.',
        'For a while neither of them spoke. Beyond the glass, the street was beginning to shine in the late light. The girl turned a page. Ingrid returned to her own book.',
        'When the clock struck five, the girl looked up. “May I come back tomorrow?”',
        '“Of course.”',
        '“We might find the other one.”',
        '“We might.”',
        'Ingrid left the chair where it was. She would put a second one beside it in the morning.',
    ]),
    ('En stille stund', [
        'Regnen holdt op klokken fire. Ingrid lagde bogen fra sig med en finger mellem siderne. Der var en time, til hun skulle lukke.',
        'Hun kunne tage regnskabet. Hun kunne åbne kassen under disken. I stedet rykkede hun stolen hen til vinduet og fandt tilbage til sin side.',
        'En pige i en gul frakke kom ind. Hun tørrede omhyggeligt støvlerne på måtten og holdt et foldet stykke papir mellem hænderne.',
        '»Jeg leder efter en bog,« sagde hun.',
        '»Så er du kommet det rette sted hen.«',
        'Pigen smilede. »Jeg kan ikke huske, hvad den hedder.«',
        '»Fortæl mig noget, du kan huske.«',
        'Der havde været et hus ved havet. En bror og en søster. Et træ, som lænede sig ind mod køkkenvinduet. Måske var træet kun med på billedet. Måske var havet en sø.',
        'Ingrid lyttede. En bog, man huskede, bestod sjældent af titler og forfatternavne. Den bestod af eftermiddage, stemmer og et rum, hvor nogen havde læst højt.',
        'De fandt ikke bogen den dag. Men de fandt en anden, som pigen kendte. Ingrid hentede te, og pigen satte sig ved vinduet.',
        'Regnskabet kunne vente. Kassen under disken kunne også vente.',
        'Dansk bruger bogstaverne æ, ø og å. Her er de i ord, der hører til på en almindelig bogside: glæde, øjeblik, lænestol, blå himmel og en åben dør.',
    ]),
]
css = 'body{font-family:serif;line-height:1.5;margin:6%;}h1{font-weight:normal;margin:1.5em 0;}p{text-indent:1.2em;margin:.4em 0;}h1+p{text-indent:0;}img{max-width:100%;height:auto;}'
with zipfile.ZipFile(assets / 'sample.epub', 'w', compression=zipfile.ZIP_DEFLATED) as z:
    z.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
    z.writestr('META-INF/container.xml', '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    z.writestr('EPUB/cover.png', (assets / 'sample-cover.png').read_bytes())
    z.writestr('EPUB/style.css', css)
    def page(title, body):
        return f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en"><head><title>{escape(title)}</title><link rel="stylesheet" href="style.css"/></head><body>{body}</body></html>'
    z.writestr('EPUB/cover.xhtml', page('Cover', '<img src="cover.png" alt="The Quiet Hour, sample book cover"/>'))
    nav = ''.join(f'<li><a href="chapter{i}.xhtml">{escape(title)}</a></li>' for i, (title, ps) in enumerate(chapters, 1))
    z.writestr('EPUB/nav.xhtml', page('Contents', f'<nav epub:type="toc"><h1>Contents</h1><ol>{nav}</ol></nav>'))
    for i, (title, ps) in enumerate(chapters, 1):
        lang = ' lang="da"' if i == 3 else ''
        body = f'<section{lang}><h1>{escape(title)}</h1>' + ''.join(f'<p id="p{j}">{escape(p)}</p>' for j, p in enumerate(ps)) + '</section>'
        z.writestr(f'EPUB/chapter{i}.xhtml', page(title, body))
    items = ''.join(f'<item id="ch{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 4))
    spine = '<itemref idref="cover"/>' + ''.join(f'<itemref idref="ch{i}"/>' for i in range(1, 4))
    z.writestr('EPUB/package.opf', f'<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:folio-sample-001</dc:identifier><dc:title>The Quiet Hour</dc:title><dc:creator>Folio</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-03T00:00:00Z</meta></metadata><manifest><item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/><item id="coverimg" href="cover.png" media-type="image/png" properties="cover-image"/><item id="css" href="style.css" media-type="text/css"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>{items}</manifest><spine>{spine}</spine></package>')
(assets / 'sample-cover.png').unlink()
print('Sample EPUB generated')
