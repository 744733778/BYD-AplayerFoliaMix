package remix.myplayer.ui.widget.lyric.folia

/**
 * 特效歌词 GLES 3.x 渲染管线着色器源码（GLSL ES 3.00）。
 *
 * 管线（对齐 folia 的 Pixi 后处理链）：
 *  1. 文字层：纹理化文字，tint + 扫光填充（sweep）+ 可选 9-tap 高斯（上下文行景深模糊）；
 *  2. 亮部提取（bright pass）→ 多级降采样可分离高斯（bloom 链）→ 合成；
 *  3. 粒子（花火）：GL_POINTS + 加法混合，参与 bloom；
 *  4. 镜台 3D：文字 billboard quad + 透视 MVP；
 *  5. 流光：合成时对 bloom 层做 UV 扰动 + 色相偏移。
 *
 * 所有 uniform 名与 [FoliaLyricGLRenderer] 中的绑定一一对应。
 */

internal object FoliaLyricShaders {

  // ------------------------------------------------------------------------------------------
  // 通用
  // ------------------------------------------------------------------------------------------

  /** 全屏 / quad 顶点着色器：unit quad + MVP + UV 矩形。 */
  const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;      // unit quad: (0,0)-(1,1)
layout(location = 1) in vec2 aUV;       // (0,0)-(1,1)
uniform mat4 uMVP;
uniform vec4 uUvRect;                   // xy = uv 偏移, zw = uv 缩放（纹理图集内取子区）
out vec2 vUV;
void main() {
  vUV = aUV * uUvRect.zw + uUvRect.xy;
  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""

  /**
   * 全屏三角形（覆盖 NDC，无 VBO）顶点着色器。
   *
   * 注意：本 VS **不接收 uMVP**——unit quad 的 (0,0)-(1,1) 必须在 VS 内部
   * 映射到 NDC (-1,-1)-(1,1)，否则只覆盖屏幕第一象限（1/4 面积），
   * 其余区域留下未初始化显存（黑屏 / 花屏）。
   */
  const val FULLSCREEN_VS = """#version 300 es
layout(location = 0) in vec2 aPos;      // unit quad: 0..1
out vec2 vUV;
void main() {
  vUV = aPos;
  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
}
"""

  // ------------------------------------------------------------------------------------------
  // 文字层：tint + 扫光 + 可选高斯
  // ------------------------------------------------------------------------------------------

  /**
   * 文字片元着色器。
   *
   * - 纹理为白字透明底（padding 已预留模糊半径）；
   * - uFillX = 扫光前沿（0..1，相对本行纹理宽度）；前沿 softstep 软边；
   * - 墨迹渐变（对齐 Folia）：1.00 → 0.92(68%) → 0.72(100%)；
   * - uBlurRadiusPx > 0 时对 alpha 做 3x3 高斯（上下文行景深）。
   */
  const val TEXT_FS = """#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
in vec2 vUV;
uniform sampler2D uTex;
uniform vec4 uUnsungColor;    // rgb + alpha（未唱）
uniform vec4 uSungColor;      // rgb + alpha（已唱/填充）
uniform float uFillX;         // 0..1
uniform float uEdgePx;        // 前沿软边宽度（px）
uniform float uLineWidthPx;   // 本行纹理的文字区宽度（px）
uniform float uBlurRadiusPx;  // 0 = 不模糊
uniform vec2 uTexSize;        // 纹理尺寸（px）
uniform float uAlpha;         // 轨道整体透明度
uniform float uTextX0;        // 文字区起点（0..1，相对纹理宽）
uniform float uTextW;         // 文字区宽度（0..1，相对纹理宽）
out vec4 fragColor;

float textAlpha(vec2 uv) { return texture(uTex, uv).a; }

void main() {
  float a = textAlpha(vUV);
  if (a <= 0.002) discard;

  // 3x3 高斯（展开采样，规避 GLSL ES 3.00 对 const 数组构造器的驱动差异）
  if (uBlurRadiusPx > 0.5) {
    vec2 t = uBlurRadiusPx / uTexSize;
    float c = textAlpha(vUV) * 4.0;
    float n = textAlpha(vUV + vec2(0.0, -t.y)) * 2.0;
    float s = textAlpha(vUV + vec2(0.0, t.y)) * 2.0;
    float w = textAlpha(vUV + vec2(-t.x, 0.0)) * 2.0;
    float e = textAlpha(vUV + vec2(t.x, 0.0)) * 2.0;
    float nw = textAlpha(vUV + vec2(-t.x, -t.y));
    float ne = textAlpha(vUV + vec2(t.x, -t.y));
    float sw = textAlpha(vUV + vec2(-t.x, t.y));
    float se = textAlpha(vUV + vec2(t.x, t.y));
    a = (c + n + s + w + e + nw + ne + sw + se) / 16.0;
    if (a <= 0.002) discard;
  }

  // 扫光：前沿左侧为 sung（vUV.x 为整纹理坐标，与 uFillX 同系）
  float edge = uEdgePx / max(uLineWidthPx, 1.0);
  float fillMask = 1.0 - smoothstep(uFillX - edge, uFillX + edge, vUV.x);

  // 墨迹渐变（按文字区归一化，对齐 Canvas 路径的沿字形渐变）：1.00 → 0.92@68% → 0.72@100%
  float inkPos = (vUV.x - uTextX0) / max(uTextW, 1e-4);
  float ink = 1.0
    - 0.08 * smoothstep(0.0, 0.68, inkPos)
    - 0.20 * smoothstep(0.68, 1.0, inkPos);

  float baseA = a * uUnsungColor.a;
  float litA = a * uSungColor.a * fillMask * ink;
  float outA = litA + baseA * (1.0 - litA);
  vec3 rgb = (uSungColor.rgb * litA + uUnsungColor.rgb * baseA * (1.0 - litA))
    / max(outA, 1e-4);
  fragColor = vec4(rgb, outA * uAlpha);
}
"""

  // ------------------------------------------------------------------------------------------
  // 后处理：亮部提取 / 可分离高斯 / 合成
  // ------------------------------------------------------------------------------------------

  /** 亮部提取：阈值以上部分按亮度比例贡献。 */
  const val BRIGHT_FS = """#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
in vec2 vUV;
uniform sampler2D uTex;
uniform float uThreshold;
out vec4 fragColor;
void main() {
  vec3 c = texture(uTex, vUV).rgb;
  float l = max(max(c.r, c.g), c.b);
  float contrib = max(l - uThreshold, 0.0) / max(l, 1e-4);
  fragColor = vec4(c * contrib, 1.0);
}
"""

  /** 可分离高斯（线性采样 5 tap，标准权重）。 */
  const val BLUR_FS = """#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
in vec2 vUV;
uniform sampler2D uTex;
uniform vec2 uDirection;   // 像素方向的步长（已含 1/size）
out vec4 fragColor;
void main() {
  vec3 sum = texture(uTex, vUV).rgb * 0.2270270270;
  sum += texture(uTex, vUV + uDirection * 1.3846153846).rgb * 0.3162162162;
  sum += texture(uTex, vUV - uDirection * 1.3846153846).rgb * 0.3162162162;
  sum += texture(uTex, vUV + uDirection * 3.2307692308).rgb * 0.0702702703;
  sum += texture(uTex, vUV - uDirection * 3.2307692308).rgb * 0.0702702703;
  fragColor = vec4(sum, 1.0);
}
"""

  /**
   * 合成：场景 + 多级 bloom（可选流光 UV 扰动）+ 暗角。
   * 封面背景以 tiny 纹理双线性放大（廉价高斯），叠加主题纱罩。
   */
  const val COMPOSITE_FS = """#version 300 es
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
in vec2 vUV;
uniform sampler2D uScene;
uniform sampler2D uBloom1;
uniform sampler2D uBloom2;
uniform sampler2D uBloom3;
uniform float uBloomWeight1;
uniform float uBloomWeight2;
uniform float uBloomWeight3;
uniform float uTime;        // 秒
uniform float uFlow;        // 0/1：流光扰动开关
uniform float uVignette;    // 0..1 暗角强度
uniform vec3 uScrimTop;     // 顶部纱罩
uniform vec3 uScrimBottom;  // 底部纱罩
out vec4 fragColor;

void main() {
  vec3 col = texture(uScene, vUV).rgb;

  vec2 bUV = vUV;
  if (uFlow > 0.5) {
    bUV += vec2(
      0.0035 * sin(vUV.y * 34.0 + uTime * 1.6),
      0.0030 * cos(vUV.x * 27.0 + uTime * 1.1)
    );
  }
  vec3 bloom = texture(uBloom1, bUV).rgb * uBloomWeight1
    + texture(uBloom2, bUV).rgb * uBloomWeight2
    + texture(uBloom3, bUV).rgb * uBloomWeight3;
  col += bloom;

  // 主题纱罩（顶 → 底）
  vec3 scrim = mix(uScrimTop, uScrimBottom, clamp(vUV.y, 0.0, 1.0));
  col = col * (1.0 - 0.55) + scrim * 0.55;

  // 暗角
  vec2 d = vUV - 0.5;
  float vig = 1.0 - dot(d, d) * uVignette;
  col *= clamp(vig, 0.0, 1.0);

  fragColor = vec4(col, 1.0);
}
"""

  // ------------------------------------------------------------------------------------------
  // 粒子（花火）
  // ------------------------------------------------------------------------------------------

  /**
   * 粒子顶点着色器：每粒子一个 GL_POINT。
   * 属性布局（interleaved，stride = 7 floats）：
   *   0-1: 屏幕位置（px，左上原点）  2: 尺寸（px）  3-5: rgb  6: alpha
   */
  const val PARTICLE_VS = """#version 300 es
layout(location = 0) in vec2 aPosPx;
layout(location = 1) in float aSizePx;
layout(location = 2) in vec3 aColor;
layout(location = 3) in float aAlpha;
uniform vec2 uViewport;     // 视口尺寸（px）
uniform mat4 uMVP;          // 可选 3D 变换（镜台模式）
out vec3 vColor;
out float vAlpha;
void main() {
  vColor = aColor;
  vAlpha = aAlpha;
  vec4 world = uMVP * vec4(aPosPx, 0.0, 1.0);
  vec2 ndc = world.xy / uViewport * 2.0 - 1.0;
  gl_Position = vec4(ndc.x, -ndc.y, world.z, 1.0);
  gl_PointSize = clamp(aSizePx, 1.0, 128.0);
}
"""

  /** 粒子片元：柔边圆点（加法混合由渲染器开启）。 */
  const val PARTICLE_FS = """#version 300 es
precision mediump float;
in vec3 vColor;
in float vAlpha;
out vec4 fragColor;
void main() {
  float d = length(gl_PointCoord - vec2(0.5));
  float a = smoothstep(0.5, 0.05, d) * vAlpha;
  if (a <= 0.003) discard;
  fragColor = vec4(vColor * a, a);
}
"""

  // ------------------------------------------------------------------------------------------
  // 镜台 3D（与文字层共用 TEXT_FS；此 VS 带 3D 定位）
  // ------------------------------------------------------------------------------------------

  /**
   * 镜台顶点着色器：行 quad 在 3D 空间中的位置由 CPU 端写入 aPosPx/aZ，
   * 透视矩阵在 CPU 端构造（uMVP），quad 始终面向相机（billboard）。
   */
  const val DIORAMA_VS = QUAD_VS
}
