"""Six original editable vector proposals; none replaces the selected launcher."""
from pathlib import Path
from html import escape
ROOT=Path(__file__).resolve().parent/'icons-0.14'
ART=[
('Folded pages','#14645c','#f9f6ec', '''<path d="M25 31 24 18 39 28Q54 20 69 28L84 18 83 43Q86 56 76 64L76 82Q64 79 54 87Q44 79 32 82V64Q22 56 25 43Z" fill="{ink}"/><path d="M32 56Q44 54 54 63Q64 54 76 56M54 63V86" fill="none" stroke="{bg}" stroke-width="5" stroke-linecap="round"/><path d="M36 42h4m28 0h4" stroke="{bg}" stroke-width="5" stroke-linecap="round"/>'''),
('Bookmark cat','#202c48','#f9f5ea', '''<rect x="24" y="27" width="60" height="57" rx="9" fill="{ink}"/><path d="M34 27V84M44 70H73" stroke="{bg}" stroke-width="4" stroke-linecap="round"/><path d="M47 26V17L58 23 69 17V26Q77 43 69 56L58 49 47 56Q39 43 47 26" fill="#e69b73"/><path d="M52 34h1m10 0h1" stroke="{bg}" stroke-width="4" stroke-linecap="round"/>'''),
('Sleeping chapter','#f2e9da','#276b5c', '''<path d="M20 66Q38 60 54 70Q70 60 88 66V85Q68 80 54 88Q40 80 20 85Z" fill="{ink}"/><path d="M54 71V86" stroke="{bg}" stroke-width="4"/><path d="M31 53Q23 27 46 26Q72 23 78 46Q81 62 64 65H39Q29 64 31 53Z" fill="#cd7955"/><path d="M35 42 33 28 46 36M58 55Q77 46 65 39Q53 32 47 42" fill="none" stroke="{bg}" stroke-width="5" stroke-linecap="round"/><path d="M38 47Q42 50 46 47" stroke="{bg}" stroke-width="3" fill="none" stroke-linecap="round"/>'''),
('Folio monogram','#6653a0','#f5eedb', '''<path d="M29 84V24H78V39H45V49H70V63H45V84Z" fill="{ink}"/><path d="M49 24 48 14 59 21 71 15 71 27Z" fill="#bde3ca"/><path d="M55 31h2m9 0h2" stroke="{bg}" stroke-width="3" stroke-linecap="round"/><path d="M77 74 85 83 69 84Z" fill="#bde3ca"/>'''),
('Open-book cat','#217ca3','#f6f6eb', '''<path d="M21 35Q40 29 53 43L54 86Q39 75 21 80Z" fill="{ink}"/><path d="M87 35Q69 29 55 43L54 86Q71 75 87 80Z" fill="#bce9e0"/><path d="M32 37 30 19 47 31 61 31 78 19 76 44Q69 57 54 58Q39 56 32 44" fill="{ink}"/><path d="M39 41h3m24 0h3M51 48h6" stroke="{bg}" stroke-width="4" stroke-linecap="round"/><path d="M54 59V83" stroke="{bg}" stroke-width="4"/>'''),
('Night reader','#233447','#f7f1dd', '''<path d="M24 70Q39 65 54 73Q69 65 84 70V84Q67 79 54 88Q40 79 24 84Z" fill="{ink}"/><path d="M54 74V86" stroke="{bg}" stroke-width="4"/><path d="M79 45Q73 20 49 24Q28 28 29 48Q31 65 50 64Q67 65 75 54Q60 61 49 51Q39 41 48 31Q61 29 66 42L69 30Z" fill="#e9b65f"/><path d="M67 47 70 39 76 45" fill="#e9b65f"/><path d="M61 48Q64 51 68 48" fill="none" stroke="{bg}" stroke-width="3" stroke-linecap="round"/>'''),
]

def main():
    ROOT.mkdir(exist_ok=True)
    board=['<svg xmlns="http://www.w3.org/2000/svg" width="1560" height="1260" viewBox="0 0 1560 1260"><rect width="1560" height="1260" fill="#f4f1e9"/><text x="60" y="72" font-family="Segoe UI,sans-serif" font-size="36" fill="#263d35">Folio · six new directions</text><text x="60" y="110" font-family="Segoe UI,sans-serif" font-size="18" fill="#66736b">Original cat + book marks · large, small launcher, circular mask and monochrome studies</text>']
    for i,(name,bg,ink,body) in enumerate(ART,1):
        drawing=body.format(ink=ink,bg=bg)
        svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="23" fill="{bg}"/>{drawing}</svg>'
        (ROOT/f'{i}-{name.lower().replace(" ","-")}.svg').write_text(svg,encoding='utf-8')
        x=60+((i-1)%3)*500;y=155+((i-1)//3)*530
        board.append(f'<g transform="translate({x},{y})"><rect width="460" height="490" rx="24" fill="#fffefb"/><text x="25" y="42" font-family="Segoe UI,sans-serif" font-size="23" font-weight="600" fill="#273d34">{i:02d} · {escape(name)}</text>')
        board.append(f'<g transform="translate(92,70) scale(2.55)"><rect width="108" height="108" rx="23" fill="{bg}"/>{drawing}</g>')
        for j,size in enumerate((28,48)):
            board.append(f'<g transform="translate({30+j*65},385) scale({size/108})"><rect width="108" height="108" rx="23" fill="{bg}"/>{drawing}</g>')
        board.append(f'<defs><clipPath id="circle{i}"><circle cx="54" cy="54" r="54"/></clipPath></defs><g transform="translate(190,373) scale(.66)" clip-path="url(#circle{i})"><rect width="108" height="108" fill="{bg}"/>{drawing}</g>')
        # A silhouette study, not the final Android monochrome resource.
        import re
        mono=re.sub(r'#[0-9a-fA-F]{6}',lambda m:'#fffefb' if m.group()==bg else '#273d34',drawing)
        board.append(f'<g transform="translate(305,373) scale(.66)">{mono}</g><text x="25" y="465" font-family="Segoe UI,sans-serif" font-size="14" fill="#6b756d">28 px · 48 px · circle · monochrome study</text></g>')
    board.append('</svg>')
    (ROOT/'comparison.svg').write_text(''.join(board),encoding='utf-8')
    (ROOT/'README.md').write_text('# Launcher proposals for Folio 0.14\n\nSix original editable vectors. Existing app icon retained until the user chooses.\n\n'+''.join(f'{i}. {row[0]}\n' for i,row in enumerate(ART,1)),encoding='utf-8')

if __name__=='__main__':main()
