"""Original Cabin Story vector illustrations. No stock images or external fonts."""
from pathlib import Path

ART = Path(__file__).resolve().parents[1] / 'app/src/main/assets/art'
ART.mkdir(parents=True, exist_ok=True)

FOX = '''<g transform="translate(740 375) rotate(-5)">
<path d="M72 131Q176 153 167 72Q132 122 89 103" fill="#ba7445"/>
<path d="M150 102Q169 89 167 72Q150 84 138 99" fill="#f8efd8"/>
<ellipse cx="62" cy="125" rx="46" ry="52" fill="#cb8551"/>
<path d="M42 104Q63 83 82 107L85 147Q61 167 38 148Z" fill="#fff4dc"/>
<path d="M22 54L14 -15L63 18L105 -20L111 57Q104 103 66 111Q26 107 22 54" fill="#d3925f"/>
<path d="M24 9L29 51L51 31Z M94 7L77 32L103 48Z" fill="#694b3d"/>
<path d="M24 55Q46 49 65 74Q85 48 109 53Q107 99 65 109Q28 100 24 55" fill="#fff4dc"/>
<path d="M41 63Q46 57 51 62M80 62Q85 57 91 61" fill="none" stroke="#3a453b" stroke-width="4" stroke-linecap="round"/>
<path d="M59 80Q65 76 71 80L65 87Z" fill="#3a453b"/>
<path d="M65 87L65 92M57 94Q65 99 73 93" stroke="#3a453b" fill="none" stroke-width="2.5" stroke-linecap="round"/>
<ellipse cx="39" cy="77" rx="8" ry="4" fill="#dfa07b"/><ellipse cx="91" cy="77" rx="8" ry="4" fill="#dfa07b"/>
<path d="M27 109Q61 127 99 107L96 125Q61 138 29 125Z" fill="#2d6656"/>
<path d="M89 119L111 157L86 154L75 127Z" fill="#2d6656"/>
<path d="M35 164L32 180M83 163L89 177" stroke="#694b3d" stroke-width="14" stroke-linecap="round"/>
</g>'''

def wrap(scene, night=False):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="600" viewBox="0 0 1000 600">
<defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="{'#264b51' if night else '#dce9df'}"/><stop offset="1" stop-color="{'#75978c' if night else '#f5f0da'}"/></linearGradient>
<filter id="paper"><feTurbulence type="fractalNoise" baseFrequency=".7" numOctaves="3" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/><feComponentTransfer><feFuncA type="linear" slope=".045"/></feComponentTransfer><feBlend in="SourceGraphic" mode="multiply"/></filter></defs>
<g filter="url(#paper)"><rect width="1000" height="600" fill="url(#sky)"/>
<circle cx="785" cy="112" r="46" fill="{'#f4e3ad' if night else '#f1d28a'}"/>
<path d="M0 307Q163 176 357 303Q532 195 703 300Q884 234 1000 301L1000 600L0 600Z" fill="{'#456957' if night else '#abc3a5'}"/>
<path d="M0 367Q228 267 413 360Q723 260 1000 366L1000 600L0 600Z" fill="{'#365d4e' if night else '#8eac87'}"/>
<path d="M0 448Q230 387 481 430Q748 381 1000 445L1000 600L0 600Z" fill="{'#77957a' if night else '#c2cda0'}"/>
{scene}
<path d="M0 565Q177 501 319 534Q616 482 1000 545L1000 600L0 600Z" fill="#d5d9b4"/>
<g fill="#f5ebce"><ellipse cx="113" cy="522" rx="4" ry="9"/><ellipse cx="126" cy="535" rx="4" ry="8"/><ellipse cx="614" cy="526" rx="4" ry="9"/></g>
{FOX}</g></svg>'''

tower = '''<ellipse cx="452" cy="483" rx="197" ry="21" fill="#9baa83"/>
<path d="M362 458Q401 362 401 244L499 244Q499 362 539 458Z" fill="#c8b899" stroke="#8b8774" stroke-width="3"/>
<path d="M365 458Q402 365 401 244L426 244Q431 365 405 458Z" fill="#dfd0ac"/>
<path d="M392 244L392 218L507 218L507 244Z" fill="#dbcaab" stroke="#8b8774" stroke-width="3"/>
<path d="M377 216L377 199L522 199L522 216Z" fill="#a9a48b" stroke="#817e6e" stroke-width="3"/>
<path d="M350 458L350 477L550 477L550 458Z" fill="#a99f86" stroke="#8b8774" stroke-width="3"/>
<path d="M429 334L429 303L471 303L471 334Z" fill="#4c5b51"/>
<g stroke="#918a75" stroke-width="2" fill="none" opacity=".75">
<path d="M401 258H499M400 278H500M398 299H501M396 321H504M393 343H507M390 365H511M385 388H517M378 411H524M371 434H531"/>
<path d="M427 244V258M466 244V258M445 258V278M481 278V299M420 278V299M455 343V365M418 365V388M478 365V388M452 388V411M401 411V434M493 411V434M450 434V458"/></g>
<g stroke="#f8f0d8" stroke-width="3" stroke-linecap="round"><path d="M633 189v20M623 199h20M294 136v14M287 143h14"/></g>
<path d="M198 394Q219 275 243 394M220 397L220 455" stroke="#58765a" stroke-width="8" fill="#819b70"/>
<path d="M659 392Q677 290 698 392M679 392V467" stroke="#58765a" stroke-width="8" fill="#819b70"/>'''

mounds='''<path d="M85 463Q117 233 333 249Q452 254 491 463Z" fill="#699269"/>
<path d="M309 469Q356 291 526 300Q681 302 711 469Z" fill="#82a475"/>
<path d="M86 463Q127 276 274 260Q156 318 151 463Z" fill="#96af7c"/>
<path d="M0 530Q312 460 501 487Q601 492 684 525" fill="none" stroke="#eae0bd" stroke-width="27"/>
<path d="M578 345L584 438M584 377L561 360" stroke="#625c46" stroke-width="7"/>
<g fill="#d1bb75"><circle cx="577" cy="323" r="42"/><circle cx="547" cy="345" r="31"/><circle cx="608" cy="347" r="35"/></g>'''

pond='''<ellipse cx="440" cy="459" rx="388" ry="98" fill="#92b0a3"/>
<path d="M135 461Q285 424 471 450M256 482Q416 457 610 480M172 510H424M518 508H633" fill="none" stroke="#c4d3b7" stroke-width="5" stroke-linecap="round"/>
<path d="M234 303L234 400M308 292L308 399M391 299L391 402M459 304L459 402" stroke="#825a43" stroke-width="13"/>
<path d="M205 397H484V411H205Z" fill="#a99778"/>
<path d="M186 303Q278 293 337 237Q403 292 494 303Q421 324 337 314Q259 326 186 303Z" fill="#3d6357"/>
<path d="M186 303Q278 293 337 237Q403 292 494 303" fill="none" stroke="#739184" stroke-width="6"/>
<path d="M235 328H459M235 350H459M235 374H459" fill="none" stroke="#cfb37b" stroke-width="5"/>
<g fill="#e8c988"><rect x="258" y="323" width="24" height="53"/><rect x="328" y="323" width="24" height="53"/><rect x="405" y="323" width="24" height="53"/></g>
<path d="M337 425L268 472L411 472Z" fill="#d7c18f" opacity=".24"/>
<g fill="#eee7c1"><circle cx="174" cy="111" r="2"/><circle cx="353" cy="93" r="3"/><circle cx="494" cy="148" r="2"/><circle cx="639" cy="67" r="2"/></g>'''

museum='''<rect x="157" y="313" width="480" height="152" fill="#ddd2b5"/>
<path d="M125 317L395 231L669 317Z" fill="#4d6f60"/><path d="M164 328H631" stroke="#b2a186" stroke-width="7"/>
<path d="M196 339V460M276 339V460M516 339V460M596 339V460" stroke="#a18c6e" stroke-width="13"/>
<rect x="328" y="344" width="136" height="120" fill="#637d6c"/><path d="M396 344V464" stroke="#d5c6a0" stroke-width="7"/>
<path d="M139 465H653L674 484H120Z" fill="#b7ab8e"/>
<path d="M332 484L291 544H502L461 484" fill="#e4dcc0"/>
<path d="M71 424V471M714 421V481" stroke="#7c7358" stroke-width="8"/><g fill="#8da575"><circle cx="71" cy="394" r="44"/><circle cx="714" cy="395" r="44"/></g>'''

for key, scene, night in [('cheomseongdae',tower,False),('daereungwon',mounds,False),('wolji',pond,True),('museum',museum,False)]:
    (ART / f'{key}.svg').write_text(wrap(scene,night),encoding='utf-8')
print('Created four original SVG landscapes with Lulu the fox.')
