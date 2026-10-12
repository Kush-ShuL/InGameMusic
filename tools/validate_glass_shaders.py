"""Compile and exercise the shipped glass shaders with a headless Mesa EGL context.

Linux developer check; uses system libEGL/libGL and the Python standard library.
Does not start Minecraft. Output uses a synthetic checkerboard, not a game capture.
"""
from pathlib import Path
import ctypes as C
import struct
import zlib

ROOT = Path(__file__).resolve().parent.parent
SHADERS = ROOT / 'src/main/resources/assets/ingamemusic/shaders/core'
EGL, GL = C.CDLL('libEGL.so.1'), C.CDLL('libGL.so.1')

def function(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn

P, I, U, F = C.c_void_p, C.c_int, C.c_uint, C.c_float
function(EGL, 'eglGetProcAddress', P, C.c_char_p)
get_display = C.CFUNCTYPE(P, U, P, P)(EGL.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
display = get_display(0x31DD, None, None)
function(EGL, 'eglInitialize', U, P, P, P)
assert EGL.eglInitialize(display, None, None), 'EGL initialization failed'
function(EGL, 'eglBindAPI', U, U)
assert EGL.eglBindAPI(0x30A2), 'OpenGL API unavailable'
function(EGL, 'eglChooseConfig', U, P, P, P, I, P)
attributes = (I * 13)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
config, count = P(), I()
assert EGL.eglChooseConfig(display, attributes, C.byref(config), 1, C.byref(count)) and count.value
function(EGL, 'eglCreateContext', P, P, P, P, P)
context_attributes = (I * 7)(0x3098, 3, 0x30FB, 2, 0x30FD, 1, 0x3038)
context = EGL.eglCreateContext(display, config, None, context_attributes)
assert context, 'OpenGL 3.2 context unavailable'
function(EGL, 'eglMakeCurrent', U, P, P, P, P)
assert EGL.eglMakeCurrent(display, None, None, context)

for name, result, args in [
    ('glCreateShader', U, (U,)), ('glShaderSource', None, (U,I,P,P)), ('glCompileShader',None,(U,)),
    ('glGetShaderiv',None,(U,U,P)), ('glGetShaderInfoLog',None,(U,I,P,P)),
    ('glCreateProgram',U,()), ('glAttachShader',None,(U,U)), ('glLinkProgram',None,(U,)),
    ('glGetProgramiv',None,(U,U,P)), ('glGetProgramInfoLog',None,(U,I,P,P)), ('glUseProgram',None,(U,)),
    ('glGetUniformBlockIndex',U,(U,C.c_char_p)), ('glUniformBlockBinding',None,(U,U,U)),
    ('glGenVertexArrays',None,(I,P)), ('glBindVertexArray',None,(U,)),
    ('glGenTextures',None,(I,P)), ('glBindTexture',None,(U,U)), ('glTexParameteri',None,(U,U,I)),
    ('glTexImage2D',None,(U,I,I,I,I,I,U,U,P)),
    ('glGenFramebuffers',None,(I,P)), ('glBindFramebuffer',None,(U,U)),
    ('glFramebufferTexture2D',None,(U,U,U,U,I)), ('glCheckFramebufferStatus',U,(U,)),
    ('glGenBuffers',None,(I,P)), ('glBindBuffer',None,(U,U)), ('glBufferData',None,(U,C.c_ssize_t,P,U)),
    ('glBindBufferBase',None,(U,U,U)), ('glViewport',None,(I,I,I,I)), ('glDrawArrays',None,(U,I,I)),
    ('glReadPixels',None,(I,I,I,I,U,U,P)), ('glGetError',U,()),
    ('glDeleteTextures',None,(I,P)), ('glDeleteFramebuffers',None,(I,P)),
    ('glDeleteBuffers',None,(I,P)), ('glDeleteVertexArrays',None,(I,P)),
    ('glDeleteShader',None,(U,)), ('glDeleteProgram',None,(U,)),
]: function(GL,name,result,*args)

programs, shaders, textures = [], [], []

def shader(path, kind, source_bytes=None):
    source = C.c_char_p(path.read_bytes() if source_bytes is None else source_bytes)
    handle = GL.glCreateShader(kind); shaders.append(handle)
    GL.glShaderSource(handle,1,C.byref(source),None); GL.glCompileShader(handle)
    status = I(); GL.glGetShaderiv(handle,0x8B81,C.byref(status))
    log = C.create_string_buffer(8192); GL.glGetShaderInfoLog(handle,8192,None,log)
    assert status.value, f'{path.name}: {log.value.decode()}'
    return handle

def program(fragment, fragment_source=None):
    handle=GL.glCreateProgram(); programs.append(handle)
    GL.glAttachShader(handle,shader(SHADERS/'glass_fullscreen.vsh',0x8B31))
    GL.glAttachShader(handle,shader(SHADERS/fragment,0x8B30,fragment_source))
    GL.glLinkProgram(handle)
    status=I();GL.glGetProgramiv(handle,0x8B82,C.byref(status))
    log=C.create_string_buffer(8192);GL.glGetProgramInfoLog(handle,8192,None,log)
    assert status.value, log.value.decode()
    block=GL.glGetUniformBlockIndex(handle,b'GlassData');assert block != 0xffffffff
    GL.glUniformBlockBinding(handle,block,0)
    return handle

def texture(w,h,pixels=None):
    handle=U();GL.glGenTextures(1,C.byref(handle));textures.append(handle)
    GL.glBindTexture(0x0DE1,handle)
    for name,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:GL.glTexParameteri(0x0DE1,name,value)
    GL.glTexImage2D(0x0DE1,0,0x8058,w,h,0,0x1908,0x1401,pixels)
    return handle

def render(prog,source,target,w,h,values):
    GL.glBindFramebuffer(0x8D40,fbo)
    GL.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,target,0)
    assert GL.glCheckFramebufferStatus(0x8D40)==0x8CD5
    GL.glViewport(0,0,w,h);GL.glUseProgram(prog)
    GL.glBindTexture(0x0DE1,source)
    data=(F*16)(*values)
    GL.glBindBuffer(0x8A11,ubo);GL.glBufferData(0x8A11,64,data,0x88E8);GL.glBindBufferBase(0x8A11,0,ubo)
    GL.glDrawArrays(0x0004,0,3)
    assert GL.glGetError()==0,'OpenGL render error'
    pixels=(C.c_ubyte*(w*h*4))();GL.glReadPixels(0,0,w,h,0x1908,0x1401,pixels)
    return bytes(pixels)

def png(path,w,h,pixels):
    # GPU rows are bottom-up. PNG rows are top-down.
    rows=b''.join(b'\0'+pixels[y*w*4:(y+1)*w*4] for y in range(h-1,-1,-1))
    def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b''))

def read_png(path):
    """Read the non-interlaced RGBA PNGs emitted by Java ImageIO."""
    raw=path.read_bytes(); offset=8; compressed=bytearray()
    while offset<len(raw):
        length=struct.unpack_from('!I',raw,offset)[0];kind=raw[offset+4:offset+8];chunk=raw[offset+8:offset+8+length];offset+=12+length
        if kind==b'IHDR':
            w,h,depth,color,_,_,interlace=struct.unpack('!2I5B',chunk)
            assert (depth,color,interlace)==(8,6,0)
        if kind==b'IDAT':compressed.extend(chunk)
    scan=zlib.decompress(compressed); stride=w*4; last=bytearray(stride); pixels=bytearray(); offset=0
    for _ in range(h):
        kind=scan[offset];row=bytearray(scan[offset+1:offset+1+stride]);offset+=stride+1
        for i in range(stride):
            a=row[i-4] if i>=4 else 0;b=last[i];c=last[i-4] if i>=4 else 0
            if kind==1:predict=a
            elif kind==2:predict=b
            elif kind==3:predict=(a+b)//2
            elif kind==4:
                p=a+b-c;pa,pb,pc=abs(p-a),abs(p-b),abs(p-c)
                predict=a if pa<=pb and pa<=pc else b if pb<=pc else c
            else:predict=0;assert kind==0
            row[i]=(row[i]+predict)&255
        pixels.extend(row);last=row
    return w,h,pixels

def layout_previews(blur_program,surface_program):
    """Render actual material shaders under the exported UI, over a test backdrop."""
    import json
    import math
    for manifest in sorted((ROOT/'build/previews').glob('*.glass.json')):
        layout=json.loads(manifest.read_text());fw,fh=layout['width'],layout['height'];pad=32
        w,h=fw+pad*2,fh+pad*2
        background=bytearray()
        for y in range(h):
            for x in range(w):
                tile=12 if (x//64+y//64)%2 else 0
                if x>w*.72 and y>h*.50:
                    rgb=(196+tile,183+tile,150+tile)
                else:
                    rgb=(int(28+36*x/w)+tile,int(64+58*y/h)+tile,int(85+34*math.sin(x/w*3))+tile)
                background.extend((*rgb,255))
        source=texture(w,h,(C.c_ubyte*len(background)).from_buffer_copy(background))
        horizontal=texture(w,h);vertical=texture(w,h)
        style=layout['panes'][0]['style'];radius=style['blur']
        render(blur_program,source,horizontal,w,h,[1/w,0,radius]+[0]*13)
        render(blur_program,horizontal,vertical,w,h,[0,1/h,radius]+[0]*13)
        composed=bytearray(background)
        def over(src,sw,sh,left,bottom):
            for py in range(sh):
                for px in range(sw):
                    si=(py*sw+px)*4;di=((bottom+py)*w+left+px)*4
                    alpha=src[si+3]/255
                    if alpha==0:continue
                    for channel in range(3):composed[di+channel]=round(src[si+channel]*alpha+composed[di+channel]*(1-alpha))
        for pane_data in layout['panes']:
            pw,ph=round(pane_data['w']),round(pane_data['h']);x=round(pane_data['x'])+pad;y=round(pane_data['y'])+pad
            output=texture(pw,ph)
            pixels=render(surface_program,vertical,output,pw,ph,[w,h,0,0,x,y,pw,ph,pane_data['radius'],style['refraction'],1,style.get('maskOpacity',0),0,0,0,0])
            over(pixels,pw,ph,x,h-y-ph)
        name=manifest.name.removesuffix('.glass.json')
        iw,ih,foreground=read_png(manifest.with_name(name+'-foreground.png'));assert (iw,ih)==(fw,fh)
        foreground=b''.join(foreground[y*fw*4:(y+1)*fw*4] for y in range(fh-1,-1,-1))
        over(foreground,fw,fh,pad,pad)
        png(manifest.with_name(name+'-preview.png'),w,h,composed)
        print('Rendered synthetic-background preview:',name)

vao, fbo, ubo=U(),U(),U()
try:
    GL.glGenVertexArrays(1,C.byref(vao));GL.glBindVertexArray(vao)
    GL.glGenFramebuffers(1,C.byref(fbo));GL.glGenBuffers(1,C.byref(ubo))
    blur=program('glass_blur.fsh');surface=program('glass_surface.fsh')
    w,h=320,240
    board=bytearray()
    for y in range(h):
        for x in range(w):
            high=((x//8+y//8)%2)==0
            board.extend((int(40+170*y/h) if high else 25,190 if high else 45,180 if high else 65,255))
    source=texture(w,h,(C.c_ubyte*len(board)).from_buffer_copy(board))
    intermediate,soft=texture(w,h),texture(w,h)
    identity=render(blur,source,intermediate,w,h,[0]*16)
    assert max(abs(a-b) for a,b in zip(identity,board))<=1,'Zero blur must preserve background detail and orientation'
    blurred_x=render(blur,source,intermediate,w,h,[1/w,0,12]+[0]*13)
    blurred=render(blur,intermediate,soft,w,h,[0,1/h,12]+[0]*13)
    def energy(p):return sum(abs(p[i]-p[i-4]) for i in range(4,len(p),4))
    assert energy(blurred)<energy(identity)*.5,'Blur must reduce high-frequency detail'
    # An impulse must spread as one smooth lobe, never separated copies/stripes.
    impulse=bytearray((0,0,0,255)*(w*h))
    origin=(h//2*w+w//2)*4;impulse[origin:origin+3]=bytes((255,255,255))
    impulse_source=texture(w,h,(C.c_ubyte*len(impulse)).from_buffer_copy(impulse))
    lobe=render(blur,impulse_source,intermediate,w,h,[1/w,0,24]+[0]*13)
    for x in range(1,25):
        left=origin-x*4;right=origin+x*4
        assert abs(lobe[left]-lobe[right])<=1, 'Gaussian must stay symmetric'
        assert lobe[right]<=lobe[right-4], 'Blur must not create repeated lobes/stripes'
    # Boundary taps must clamp, never wrap the opposite side of the screen.
    edge=bytearray((0,0,0,255)*(w*h))
    for y in range(h):edge[y*w*4:y*w*4+3]=bytes((255,255,255))
    edge_source=texture(w,h,(C.c_ubyte*len(edge)).from_buffer_copy(edge))
    edge_blur=render(blur,edge_source,intermediate,w,h,[1/w,0,24]+[0]*13)
    assert all(edge_blur[(y*w+w-1)*4]==0 for y in range(h)), 'Blur may not wrap across screen edges'
    pw,ph=256,176; pane=texture(pw,ph)
    def panel(src,index,mask=0):return render(surface,src,pane,pw,ph,[w,h,0,0,32,32,pw,ph,18,index,1,mask,0,0,0,0])
    neutral=panel(source,1);bent=panel(source,2)
    assert neutral[3]==0 and bent[3]==0,'Rounded corners must be transparent'
    center=((ph//2)*pw+pw//2)*4
    source_center=((h-32-ph+ph//2)*w+32+pw//2)*4
    assert max(abs(a-b) for a,b in zip(neutral[center:center+4],board[source_center:source_center+4])) <= 1, 'Clear glass must not tint or lighten the scene'
    gray=bytes(v for y in range(h) for x in range(w) for v in ((210,210,210,255) if (x//8+y//8)%2 else (35,35,35,255)))
    gray_source=texture(w,h,(C.c_ubyte*len(gray)).from_buffer_copy(gray))
    dispersive=panel(gray_source,1.7)
    assert sum(max(dispersive[i:i+3])-min(dispersive[i:i+3])>8 for i in range(0,len(dispersive),4))>100, 'Dispersion must separate RGB along refracted rays'
    neutral_gray=panel(gray_source,1)
    assert all(max(neutral_gray[i:i+3])-min(neutral_gray[i:i+3])<=1 for i in range(0,len(neutral_gray),4)), 'Index 1.00 must not introduce colored fringes'
    masked=panel(source,1,.5)
    assert max(abs(masked[center+i]-neutral[center+i]*.5) for i in range(3))<=1, 'A partial mask must darken all channels equally'
    opaque=panel(source,1,1)
    assert opaque[center:center+3]==b'\0\0\0', 'Zero mask transparency must block the scene'
    # The crown must bend the body as well as the rounded shoulder. A rim-only
    # effect cannot change any samples more than 30 pixels inside the panel.
    body_differences = sum(abs(neutral[(y*pw+x)*4]-bent[(y*pw+x)*4]) > 3
                           for y in range(32, ph-32) for x in range(32, pw-32))
    assert body_differences > 400, 'Finite-thickness curved glass must refract the interior'
    differences=[i//4 for i in range(0,len(bent),4) if abs(neutral[i]-bent[i])>3]
    assert len(differences)>200,'Increasing refractive index must visibly bend the scene'
    flat=bytes((74,131,189,255))*(w*h)
    flat_source=texture(w,h,(C.c_ubyte*len(flat)).from_buffer_copy(flat))
    flat_glass=panel(flat_source,1)
    assert all(max(abs(flat_glass[i+c]-(74,131,189)[c]) for c in range(3))<=1
               for i in range(0,len(flat_glass),4) if flat_glass[i+3]>0), 'Index 1 must transmit uniform color without any painted highlight'
    # No synthetic lighting: uniform scenery must stay uniform at every panel
    # size and index, including the former bright shoulder reflections.
    for gw,gh in ((340,94),(602,482),(880,82)):
        output_pane=texture(gw,gh)
        for index in (1,1.35,2):
            material=render(surface,flat_source,output_pane,gw,gh,[w,h,0,0,0,0,gw,gh,18,index,1,0,0,0,0,0])
            assert all(max(abs(material[i+c]-(74,131,189)[c]) for c in range(3))<=1
                       for i in range(0,len(material),4) if material[i+3]>0), 'Glass may not add artificial lighting or tint to the scene'
    # Exercise the actual GLSL geometry independently of scenery: equal aspect
    # panes at different sizes must retain their crown normal, not flatten out.
    geometry_source=(SHADERS/'glass_surface.fsh').read_text().split('void main() {')[0]
    normals=program('glass_surface.fsh',(geometry_source+"""
void main() {
    vec2 point = (texCoord - 0.5) * Rect.zw;
    vec3 shape = geometry(point);
    vec3 n = normalize(vec3(-shape.xy, 1.0));
    fragColor = vec4(n * 0.5 + 0.5, 1.0);
}
""").encode())
    samples=[]
    for gw,gh in ((256,176),(768,528)):
        normal_map=render(normals,source,texture(gw,gh),gw,gh,[w,h,0,0,0,0,gw,gh,18,1.35,1,0,0,0,0,0])
        pos=(gh//2*gw+gw*3//4)*4;samples.append(normal_map[pos:pos+3])
    assert max(abs(a-b) for a,b in zip(*samples))<=1, 'Large panels must not lose crown curvature relative to HUD-size panes'
    # Probe the derivative of the slope on both sides of the shoulder/crown join.
    # The former sine shoulder had a second-derivative jump and fails this check.
    curvature=program('glass_surface.fsh',(geometry_source+"""
void main() {
    vec2 p = Reserved.xy + vec2((texCoord.x - 0.5) * 0.8, 0.0);
    vec2 a = p + vec2(0.02, 0.0), b = p - vec2(0.02, 0.0);
    float derivative = (geometry(a).x - geometry(b).x) / 0.04;
    fragColor = vec4(vec3(0.5 + derivative * 3.0), 1.0);
}
""").encode())
    for gw,gh in ((340,94),(602,482)):
        shoulder_width=min(max(24, min(gw,gh)*.5*.22), min(gw,gh)*.5*.65)
        seam=render(curvature,source,texture(2,1),2,1,[w,h,0,0,0,0,gw,gh,18,1.35,1,0,gw*.5-shoulder_width,0,0,0])
        assert abs(seam[0]-seam[4])<=3, 'The edge and crown must join with continuous slope derivative'
    corner_probe=program('glass_surface.fsh',(geometry_source+"""
void main() {
    vec2 point = Reserved.xy + vec2((texCoord.x - 0.5) * 0.8, 0.0);
    vec3 shape = geometry(point);
    fragColor = vec4(normalize(vec3(-shape.xy, 1.0)) * 0.5 + 0.5, 1.0);
}
""").encode())
    for depth in (20,30,40):
        corner=render(corner_probe,source,texture(2,1),2,1,[w,h,0,0,0,0,602,482,18,1.35,1,0,301-depth,241-depth,0,0])
        assert max(abs(corner[c]-corner[4+c]) for c in range(3))<=2, 'Rounded corner normals must not jump across the nearest-edge diagonal'
    respect=panel(soft,1)
    assert energy(respect)<energy(neutral)*.65,'Sampling a blurred layer must not recover sharp detail'
    output=ROOT/'build/previews';output.mkdir(parents=True,exist_ok=True)
    png(output/'glass-shader-sharp.png',pw,ph,neutral)
    png(output/'glass-shader-refracted.png',pw,ph,bent)
    png(output/'glass-shader-blurred.png',pw,ph,panel(soft,1.35))
    print('PASS: GLSL compile/link, zero blur, blur reduction, no ghost stripes/edge wrapping, no artificial lighting at HUD/main sizes, C2 shoulder join, no diagonal normal seams, rounded mask, no tint, neutral index, full-surface ray refraction, Cauchy dispersion, neutral mask, blurred-source preservation.')
    import sys
    if '--previews' in sys.argv:layout_previews(blur,surface)
finally:
    for value in textures:GL.glDeleteTextures(1,C.byref(value))
    for value in programs:GL.glDeleteProgram(value)
    for value in shaders:GL.glDeleteShader(value)
    GL.glDeleteBuffers(1,C.byref(ubo));GL.glDeleteFramebuffers(1,C.byref(fbo));GL.glDeleteVertexArrays(1,C.byref(vao))
    EGL.eglMakeCurrent(display,None,None,None)
    function(EGL,'eglDestroyContext',U,P,P)(display,context)
    function(EGL,'eglTerminate',U,P)(display)
