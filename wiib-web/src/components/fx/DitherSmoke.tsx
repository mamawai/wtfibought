import { useEffect, useRef, type RefObject } from 'react';

/** 一个"大像素"占多少 CSS px，越大颗粒越粗 */
const PIXEL = 2.5;
/** 抖色量化级数，越少越"版画" */
const COLOR_NUM = 4;

/** 明暗两套烟雾配色：暗=深灰蓝烟，亮=中灰烟（太浅看不见），峰值处混一点品牌橙。
 *  bg 同 index.css 的 --color-smoke：登录卡拿它当底色，跟烟接成一片 */
const PALETTE = {
  dark: { bg: [0.043, 0.047, 0.059], ink: [0.16, 0.18, 0.23], accent: [0.976, 0.451, 0.086] },
  light: { bg: [0.965, 0.965, 0.957], ink: [0.67, 0.67, 0.64], accent: [0.976, 0.451, 0.086] },
};

/** 流动参数，单位 CSS px：风速(px/s)、风向(度，90=正上)、湍流速度、卡片外的最小空隙、空隙外烟慢慢变浓的宽度。
 *  窄屏是单列布局，卡片几乎占满宽，空隙和过渡跟着收窄 */
const FLOW = {
  wide: { wind: 18, angle: 80, turb: 16, gap: 12, soft: 80 },
  narrow: { wind: 16, angle: 85, turb: 14, gap: 8, soft: 50 },
};

const VERT = 'attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }';

const PRECISION = `
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
`;

const NOISE = `
vec4 mod289(vec4 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 permute(vec4 x){ return mod289(((x*34.0)+10.0)*x); }
vec4 taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314*r; }
vec2 fade(vec2 t){ return t*t*t*(t*(t*6.0-15.0)+10.0); }

float cnoise(vec2 P){
  vec4 Pi = floor(P.xyxy) + vec4(0.0,0.0,1.0,1.0);
  vec4 Pf = fract(P.xyxy) - vec4(0.0,0.0,1.0,1.0);
  Pi = mod289(Pi);
  vec4 ix = Pi.xzxz; vec4 iy = Pi.yyww;
  vec4 fx = Pf.xzxz; vec4 fy = Pf.yyww;
  vec4 i = permute(permute(ix) + iy);
  vec4 gx = fract(i*(1.0/41.0))*2.0 - 1.0;
  vec4 gy = abs(gx) - 0.5;
  vec4 tx = floor(gx + 0.5);
  gx = gx - tx;
  vec2 g00 = vec2(gx.x, gy.x); vec2 g10 = vec2(gx.y, gy.y);
  vec2 g01 = vec2(gx.z, gy.z); vec2 g11 = vec2(gx.w, gy.w);
  vec4 norm = taylorInvSqrt(vec4(dot(g00,g00), dot(g01,g01), dot(g10,g10), dot(g11,g11)));
  g00 *= norm.x; g01 *= norm.y; g10 *= norm.z; g11 *= norm.w;
  float n00 = dot(g00, vec2(fx.x, fy.x));
  float n10 = dot(g10, vec2(fx.y, fy.y));
  float n01 = dot(g01, vec2(fx.z, fy.z));
  float n11 = dot(g11, vec2(fx.w, fy.w));
  vec2 f = fade(Pf.xy);
  vec2 nx = mix(vec2(n00, n01), vec2(n10, n11), f.x);
  return 2.3 * mix(nx.x, nx.y, f.y);
}

float fbm(vec2 p){
  float v = 0.0, a = 1.0;
  for (int i = 0; i < 4; i++) { v += a*abs(cnoise(p)); p *= 3.0; a *= 0.3; }
  return v;
}
`;

/* 2x2 → 4x4 有序抖动矩阵，紧凑写法；浓度 f 抖色量化成颜色 */
const DITHER = `
float bayer2(vec2 a){ a = floor(a); return fract(a.x/2.0 + a.y*a.y*0.75); }
float bayer4(vec2 a){ return bayer2(0.5*a)*0.25 + bayer2(a); }

vec3 shade(float f, vec3 bg, vec3 ink, vec3 accent){
  float d = (bayer4(gl_FragCoord.xy) - 0.5) / ${COLOR_NUM.toFixed(1)};
  float q = clamp(floor((f + d) * ${COLOR_NUM.toFixed(1)}) / ${(COLOR_NUM - 1).toFixed(1)}, 0.0, 1.0);
  /* 峰值处混入品牌橙：mix 而非叠加，亮色底下才不会越加越白 */
  return mix(mix(bg, ink, q), accent, pow(q, 3.0) * 0.22);
}
`;

/* 烟的底稿和障碍物。坐标都是低分辨率像素，y 朝上 */
const SCENE = `
uniform vec2  u_res;
uniform float u_time;
uniform vec2  u_wind;     // 风，低分辨率像素/秒
uniform vec4  u_hole;     // 障碍物（登录卡）矩形 x0,y0,x1,y1；x1<=x0 表示没有
uniform float u_gap;      // 卡片外至少空出多宽
uniform float u_soft;     // 空隙外烟由淡到浓的宽度
uniform vec2  u_mouse;    // (-1,-1) 表示鼠标不在页面内

/* Perlin fbm 域扭曲出烟形，整体随风平移 */
float smokeAt(vec2 p){
  vec2 uv = (p - u_wind * u_time) / u_res - 0.5;
  uv.x *= u_res.x / u_res.y;
  return fbm(uv + fbm(uv - u_time * 0.05));
}

/* 到障碍物（卡片外扩 gap，圆角）的距离，里面为负 */
float holeDist(vec2 p){
  if (u_hole.z <= u_hole.x) return 1e4;
  vec2 k = abs(p - (u_hole.xy + u_hole.zw) * 0.5) - (u_hole.zw - u_hole.xy) * 0.5;
  return length(max(k, 0.0)) + min(max(k.x, k.y), 0.0) - u_gap;
}

vec2 holeNormal(vec2 p){
  vec2 d = p - (u_hole.xy + u_hole.zw) * 0.5;
  vec2 k = abs(d) - (u_hole.zw - u_hole.xy) * 0.5;
  vec2 s = vec2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);
  if (k.x > 0.0 && k.y > 0.0) return s * normalize(k);
  return k.x > k.y ? vec2(s.x, 0.0) : vec2(0.0, s.y);
}
`;

/* 流体一步：按流场把上一帧的烟往前推，再往底稿慢慢回拉 */
const ADVECT = PRECISION + NOISE + SCENE + `
uniform sampler2D u_src;
uniform float u_dt;
uniform float u_seed;     // 1=直接写底稿（初始化）
uniform float u_turb;     // 湍流速度，低分辨率像素/秒
uniform vec2  u_trail;    // 推烟的中心：鼠标位置的缓动跟随
uniform vec2  u_mvel;     // 鼠标速度，拖着烟走

const float BAND = 70.0;  // 绕流影响带宽
const float FREQ = 0.016; // 湍流旋涡尺度

void main(){
  vec2 p = gl_FragCoord.xy;
  float d = holeDist(p);
  float near = smoothstep(0.0, u_soft, d);
  float tgt = smokeAt(p) * near;
  if (u_seed > 0.5) { gl_FragColor = vec4(d < 0.0 ? 0.0 : tgt, 0.0, 0.0, 1.0); return; }

  /* 流函数 psi = 风 + curl-noise 湍流，速度取它的旋度，天然不可压 */
  vec2 q = (p - u_wind * u_time) * FREQ + vec2(0.0, u_time * 0.02);
  float A = u_turb / FREQ;
  float e = 0.05;
  float psi = A * cnoise(q);
  float gx = (cnoise(q + vec2(e, 0.0)) - cnoise(q - vec2(e, 0.0))) / (2.0 * e) * FREQ;
  float gy = (cnoise(q + vec2(0.0, e)) - cnoise(q - vec2(0.0, e))) / (2.0 * e) * FREQ;
  vec2 v = u_wind + A * vec2(gy, -gx);

  if (u_mouse.x >= 0.0) {
    /* 拖着烟走：沿鼠标移动方向的偶极子 */
    vec2 rm = p - u_mouse;
    float g = exp(-dot(rm, rm) / 900.0);
    float pm = (u_mvel.x * rm.y - u_mvel.y * rm.x) * g;
    psi += pm;
    v += vec2(u_mvel.x * g - rm.y / 450.0 * pm, u_mvel.y * g + rm.x / 450.0 * pm);
    /* 把烟推开：往外的径向推力，底稿也压淡，鼠标停着也留一个空洞 */
    vec2 rt = p - u_trail;
    float r = u_res.y * 0.2;
    float gr = exp(-dot(rt, rt) / (r * r));
    v += normalize(rt + vec2(0.001)) * 50.0 * gr;
    tgt *= 1.0 - 0.6 * gr;
  }

  /* 卡片当障碍物：流函数乘一个贴边为 0 的坡度（Bridson 2007），流线贴着卡片绕过去 */
  if (u_hole.z > u_hole.x) {
    vec2 c = (u_hole.xy + u_hole.zw) * 0.5;
    psi += u_wind.x * (p.y - c.y) - u_wind.y * (p.x - c.x);
    float x = clamp(d / BAND, 0.0, 1.0);
    float ramp = (15.0 * x - 10.0 * x * x * x + 3.0 * x * x * x * x * x) / 8.0;
    float dramp = x >= 1.0 ? 0.0 : (15.0 - 30.0 * x * x + 15.0 * x * x * x * x) / (8.0 * BAND);
    vec2 n = holeNormal(p);
    v = ramp * v + psi * dramp * vec2(n.y, -n.x);
  }

  /* 顺着速度往回取上一帧的烟；卡片附近回拉得慢，烟丝能贴着卡片流远 */
  float dye = texture2D(u_src, (p - v * u_dt) / u_res).r;
  dye = mix(dye, tgt, 1.0 - exp(-mix(0.08, 0.5, near) * u_dt));
  gl_FragColor = vec4(d < 0.0 ? 0.0 : dye, 0.0, 0.0, 1.0);
}
`;

const DISPLAY = PRECISION + DITHER + `
uniform sampler2D u_src;
uniform vec2 u_res;
uniform vec3 u_bg;
uniform vec3 u_ink;
uniform vec3 u_accent;
void main(){
  gl_FragColor = vec4(shade(texture2D(u_src, gl_FragCoord.xy / u_res).r, u_bg, u_ink, u_accent), 1.0);
}
`;

/* 画布尺寸变了：旧烟拉伸着拷进新纹理，不从头重来 */
const COPY = PRECISION + `
uniform sampler2D u_src;
uniform vec2 u_res;
void main(){ gl_FragColor = texture2D(u_src, gl_FragCoord.xy / u_res); }
`;

/* 不能渲染到浮点纹理时的退路：单趟程序化烟，卡片处挖空，鼠标靠近处压淡 */
const DIRECT = PRECISION + NOISE + DITHER + SCENE + `
uniform vec3 u_bg;
uniform vec3 u_ink;
uniform vec3 u_accent;
void main(){
  vec2 p = gl_FragCoord.xy;
  float d = holeDist(p);
  float f = smokeAt(p) * smoothstep(0.0, u_soft, d);
  if (u_mouse.x >= 0.0) f -= 0.55 * (1.0 - smoothstep(0.0, u_res.y * 0.55, length(p - u_mouse)));
  if (d < 0.0) f = 0.0;
  gl_FragColor = vec4(shade(f, u_bg, u_ink, u_accent), 1.0);
}
`;

type Program = { prog: WebGLProgram; u: Record<string, WebGLUniformLocation | null> };
type Target = { tex: WebGLTexture; fbo: WebGLFramebuffer };

/**
 * 抖色烟雾背景：裸 WebGL 一张低分辨率画布，CSS pixelated 放大出大颗粒。
 * 烟整体随风往上飘、带湍流打旋；传了 obstacleRef 的话，那个元素是障碍物，烟贴着它绕过去，
 * 它所在的矩形始终是空的。鼠标靠近把烟推开，移动时拖着烟走。
 * 浮点纹理不可用时退回单趟程序化烟（只挖空、不绕流）；reduced-motion 只画静态一帧；
 * WebGL 不可用时保持透明，由登录页的点阵纹理兜底。
 */
export function DitherSmoke({ className, obstacleRef }: {
  className?: string;
  obstacleRef?: RefObject<HTMLElement | null>;
}) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const cv = ref.current;
    if (!cv) return;
    const gl2 = cv.getContext('webgl2', { antialias: false, depth: false });
    const gl = (gl2 ?? cv.getContext('webgl', { antialias: false, depth: false })) as WebGLRenderingContext | null;
    if (!gl) return;

    const build = (fs: string, names: string[]): Program | null => {
      const prog = gl.createProgram();
      for (const [type, src] of [[gl.VERTEX_SHADER, VERT], [gl.FRAGMENT_SHADER, fs]] as const) {
        const s = gl.createShader(type)!;
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          console.error('DitherSmoke shader:', gl.getShaderInfoLog(s));
          return null;
        }
        gl.attachShader(prog, s);
      }
      gl.bindAttribLocation(prog, 0, 'p');
      gl.linkProgram(prog);
      const u: Program['u'] = {};
      for (const n of names) u[n] = gl.getUniformLocation(prog, n);
      return { prog, u };
    };

    const makeTarget = (w: number, h: number): Target | null => {
      if (!gl2) return null;
      const tex = gl2.createTexture();
      gl2.bindTexture(gl2.TEXTURE_2D, tex);
      gl2.texParameteri(gl2.TEXTURE_2D, gl2.TEXTURE_MIN_FILTER, gl2.LINEAR);
      gl2.texParameteri(gl2.TEXTURE_2D, gl2.TEXTURE_MAG_FILTER, gl2.LINEAR);
      gl2.texParameteri(gl2.TEXTURE_2D, gl2.TEXTURE_WRAP_S, gl2.CLAMP_TO_EDGE);
      gl2.texParameteri(gl2.TEXTURE_2D, gl2.TEXTURE_WRAP_T, gl2.CLAMP_TO_EDGE);
      gl2.texImage2D(gl2.TEXTURE_2D, 0, gl2.RGBA16F, w, h, 0, gl2.RGBA, gl2.HALF_FLOAT, null);
      const fbo = gl2.createFramebuffer();
      gl2.bindFramebuffer(gl2.FRAMEBUFFER, fbo);
      gl2.framebufferTexture2D(gl2.FRAMEBUFFER, gl2.COLOR_ATTACHMENT0, gl2.TEXTURE_2D, tex, 0);
      const ok = gl2.checkFramebufferStatus(gl2.FRAMEBUFFER) === gl2.FRAMEBUFFER_COMPLETE;
      gl2.bindFramebuffer(gl2.FRAMEBUFFER, null);
      if (ok) return { tex, fbo };
      gl2.deleteTexture(tex);
      gl2.deleteFramebuffer(fbo);
      return null;
    };
    const dropTarget = (t: Target) => { gl.deleteTexture(t.tex); gl.deleteFramebuffer(t.fbo); };

    const SCENE_U = ['u_res', 'u_time', 'u_wind', 'u_hole', 'u_gap', 'u_soft', 'u_mouse'];
    const COLOR_U = ['u_bg', 'u_ink', 'u_accent'];
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // 流体要能渲染到半精度浮点纹理：先拿 1x1 试一下
    let fluid = false;
    if (!reduced && gl2 && gl2.getExtension('EXT_color_buffer_float')) {
      const probe = makeTarget(1, 1);
      if (probe) { dropTarget(probe); fluid = true; }
    }
    const advect = fluid ? build(ADVECT, [...SCENE_U, 'u_src', 'u_dt', 'u_seed', 'u_turb', 'u_trail', 'u_mvel']) : null;
    const display = fluid ? build(DISPLAY, ['u_src', 'u_res', ...COLOR_U]) : null;
    const copy = fluid ? build(COPY, ['u_src', 'u_res']) : null;
    if (!advect || !display || !copy) fluid = false;
    const direct = fluid ? null : build(DIRECT, [...SCENE_U, ...COLOR_U]);
    if (!fluid && !direct) return;

    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.activeTexture(gl.TEXTURE0);

    // 两张纹理来回倒：读一张、写另一张；seed=还没有烟，先写一遍底稿
    let W = 0, H = 0;
    let targets: Target[] = [];
    let cur = 0;
    let seed = true;
    const resize = () => {
      const w = Math.max(1, Math.ceil(cv.clientWidth / PIXEL));
      const h = Math.max(1, Math.ceil(cv.clientHeight / PIXEL));
      if (w === W && h === H) return;
      if (fluid) {
        const a = makeTarget(w, h), b = makeTarget(w, h);
        if (!a || !b) {
          if (a) dropTarget(a);
          if (b) dropTarget(b);
          return;
        }
        if (targets.length) {
          gl.useProgram(copy!.prog);
          gl.uniform1i(copy!.u.u_src, 0);
          gl.uniform2f(copy!.u.u_res, w, h);
          gl.viewport(0, 0, w, h);
          gl.bindTexture(gl.TEXTURE_2D, targets[cur].tex);
          for (const t of [a, b]) {
            gl.bindFramebuffer(gl.FRAMEBUFFER, t.fbo);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
          }
          targets.forEach(dropTarget);
        }
        targets = [a, b];
        cur = 0;
      }
      cv.width = w;
      cv.height = h;
      W = w;
      H = h;
    };

    // ex/ey 是推烟中心（缓动跟随），px/py 是上一帧的鼠标位置（算速度用）
    const mouse = { cx: 0, cy: 0, on: false, ex: -1, ey: -1, px: -1, py: -1, vx: 0, vy: 0 };
    const onMove = (e: MouseEvent) => { mouse.cx = e.clientX; mouse.cy = e.clientY; mouse.on = true; };
    // 移出窗口：document 上的 mouseout 且 relatedTarget 为空（window 收不到 mouseleave）
    const onOut = (e: MouseEvent) => { if (!e.relatedTarget) mouse.on = false; };

    const t0 = performance.now();
    let last = t0;
    const render = (now: number) => {
      resize();
      const dt = Math.min(0.05, Math.max(0, (now - last) / 1000));
      last = now;
      const time = reduced ? 0 : (now - t0) / 1000;
      const flow = cv.clientWidth < 1024 ? FLOW.narrow : FLOW.wide;
      const pal = PALETTE[document.documentElement.classList.contains('dark') ? 'dark' : 'light'];
      const cr = cv.getBoundingClientRect();
      const sx = cr.width ? W / cr.width : 1 / PIXEL;
      const sy = cr.height ? H / cr.height : 1 / PIXEL;

      const hole = [0, 0, 0, 0];
      const ob = obstacleRef?.current;
      if (ob) {
        const r = ob.getBoundingClientRect();
        hole[0] = (r.left - cr.left) * sx;
        hole[1] = (cr.bottom - r.bottom) * sy;
        hole[2] = (r.right - cr.left) * sx;
        hole[3] = (cr.bottom - r.top) * sy;
      }

      /* 鼠标：推烟的中心缓动跟随，有"拖尾"手感；速度拿来拖着烟走 */
      let mx = -1, my = -1;
      if (mouse.on && !reduced) {
        mx = (mouse.cx - cr.left) * sx;
        my = (cr.bottom - mouse.cy) * sy;
        if (mouse.ex < 0) { mouse.ex = mx; mouse.ey = my; mouse.px = mx; mouse.py = my; }
        if (dt > 0) {
          mouse.vx += ((mx - mouse.px) / dt - mouse.vx) * 0.25;
          mouse.vy += ((my - mouse.py) / dt - mouse.vy) * 0.25;
        }
        mouse.px = mx;
        mouse.py = my;
        mouse.ex += (mx - mouse.ex) * 0.08;
        mouse.ey += (my - mouse.ey) * 0.08;
      } else {
        mouse.ex = -1;
        mouse.ey = -1;
      }
      mouse.vx *= 0.92;
      mouse.vy *= 0.92;
      const speed = Math.hypot(mouse.vx, mouse.vy);
      if (speed > 160) { mouse.vx *= 160 / speed; mouse.vy *= 160 / speed; }

      const sceneUniforms = (pg: Program) => {
        const ang = flow.angle * Math.PI / 180;
        gl.uniform2f(pg.u.u_res, W, H);
        gl.uniform1f(pg.u.u_time, time);
        gl.uniform2f(pg.u.u_wind, Math.cos(ang) * flow.wind / PIXEL, Math.sin(ang) * flow.wind / PIXEL);
        gl.uniform4f(pg.u.u_hole, hole[0], hole[1], hole[2], hole[3]);
        gl.uniform1f(pg.u.u_gap, flow.gap / PIXEL);
        gl.uniform1f(pg.u.u_soft, flow.soft / PIXEL);
      };
      const colorUniforms = (pg: Program) => {
        gl.uniform3fv(pg.u.u_bg, pal.bg);
        gl.uniform3fv(pg.u.u_ink, pal.ink);
        gl.uniform3fv(pg.u.u_accent, pal.accent);
      };

      gl.viewport(0, 0, W, H);
      if (fluid) {
        if (!targets.length) return;
        gl.useProgram(advect!.prog);
        sceneUniforms(advect!);
        gl.uniform2f(advect!.u.u_mouse, mx, my);
        gl.uniform1i(advect!.u.u_src, 0);
        gl.uniform1f(advect!.u.u_dt, dt);
        gl.uniform1f(advect!.u.u_turb, flow.turb / PIXEL);
        gl.uniform2f(advect!.u.u_trail, mouse.ex, mouse.ey);
        gl.uniform2f(advect!.u.u_mvel, mouse.vx, mouse.vy);
        if (seed) {
          gl.uniform1f(advect!.u.u_seed, 1);
          gl.bindTexture(gl.TEXTURE_2D, null);
          for (const t of targets) {
            gl.bindFramebuffer(gl.FRAMEBUFFER, t.fbo);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
          }
          seed = false;
        } else {
          gl.uniform1f(advect!.u.u_seed, 0);
          gl.bindTexture(gl.TEXTURE_2D, targets[cur].tex);
          gl.bindFramebuffer(gl.FRAMEBUFFER, targets[1 - cur].fbo);
          gl.drawArrays(gl.TRIANGLES, 0, 3);
          cur = 1 - cur;
        }
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.useProgram(display!.prog);
        gl.uniform1i(display!.u.u_src, 0);
        gl.uniform2f(display!.u.u_res, W, H);
        colorUniforms(display!);
        gl.bindTexture(gl.TEXTURE_2D, targets[cur].tex);
      } else {
        gl.useProgram(direct!.prog);
        sceneUniforms(direct!);
        gl.uniform2f(direct!.u.u_mouse, mouse.ex, mouse.ey);
        colorUniforms(direct!);
      }
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    };

    let raf = 0;
    let ro: ResizeObserver | null = null;
    if (reduced) {
      // 静态一帧：画布或卡片尺寸变了再重画
      render(t0);
      ro = new ResizeObserver(() => render(performance.now()));
      ro.observe(cv);
      if (obstacleRef?.current) ro.observe(obstacleRef.current);
    } else {
      const frame = (now: number) => {
        raf = requestAnimationFrame(frame);
        render(now);
      };
      raf = requestAnimationFrame(frame);
      window.addEventListener('mousemove', onMove);
      document.addEventListener('mouseout', onOut);
    }

    return () => {
      cancelAnimationFrame(raf);
      ro?.disconnect();
      window.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseout', onOut);
    };
  }, [obstacleRef]);

  return <canvas ref={ref} className={className} style={{ imageRendering: 'pixelated' }} aria-hidden />;
}
