package com.slowva.player.ui.theme

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/** One whole loop of the dye; every time term turns a whole period in it, so the loop wraps without a jump. */
internal const val DYE_LOOP_MS = 48_000

// Value-noise fbm moves the dye; three dyes cycle with a bleed between them, mixed like pigment (multiplied),
// pale undyed fabric shows where the folds kept the dye out, and per-pixel grain gives the cloth.
private const val TIE_DYE = """
uniform float2 size;
uniform float time;
uniform float pulse;
uniform float strength;
uniform float mode;
uniform float fade;
uniform float alpha;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

const float TAU = 6.2831853;
const float3 CREAM = float3(0.93, 0.89, 0.81);
const float3 CLOTH = float3(0.031, 0.035, 0.051);

float hash(float2 p) {
    float3 q = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = p - i;
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// smoothstep falling from 1 at x <= b to 0 at x >= a (a > b), without relying on reversed edges
float rstep(float a, float b, float x) {
    return 1.0 - smoothstep(b, a, x);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(17.1, 9.2);
        p = float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y);
        a *= 0.5;
    }
    return v / 0.9375;
}

float fbm5(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(17.1, 9.2);
        p = float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y);
        a *= 0.5;
    }
    return v / 0.96875;
}

// two dyes meeting darken like real pigment: a geometric mix, so blue and yellow bleed to green, not grey
float3 pigment(float3 a, float3 b, float t) {
    return exp(mix(log(max(a, float3(0.02))), log(max(b, float3(0.02))), t));
}

float3 pick(float i) {
    if (i < 0.5) return float3(c1.rgb);
    if (i < 1.5) return float3(c2.rgb);
    return float3(c3.rgb);
}

// the three-dye loop: one dye per unit of x, a smoothstep bleed of width soft where they meet
float3 dye(float x, float soft) {
    float k = fract(x / 3.0) * 3.0;
    float i = floor(k);
    float t = smoothstep(0.5 - soft, 0.5 + soft, fract(k));
    float j = i + 1.0;
    if (j > 2.5) j = 0.0;
    return pigment(pick(i), pick(j), t);
}

half4 main(float2 fc) {
    float s = max(size.x, size.y);
    float2 p = fc / s;
    float2 lp = size / s;
    float a0 = time * TAU;
    float2 o = 0.35 * float2(cos(a0), sin(a0));
    float wob = fbm(p * 3.0 + o) - 0.5;
    float bare = 0.0;
    float soft = 0.18;
    float x = 0.0;
    float3 col = float3(0.0);

    if (mode < 0.5) {
        // spiral: wedges curling out of one point, pale streaks along the twisted pleats
        float2 d = p - lp * float2(0.62, 0.30);
        float r = length(d) * (1.0 - 0.06 * pulse);
        float a = atan(d.y, d.x);
        float sp = a / TAU * 6.0 + r * 9.0;
        float jag = noise(float2(sp * 7.0, r * 26.0)) - 0.5;
        x = sp + wob * 1.3 + jag * 0.35 - time * 3.0;
        soft = 0.10 + 0.18 * fbm(p * 6.0 + float2(-o.y, o.x));
        float e = abs(fract(x) - 0.5);
        bare = rstep(0.06, 0.0, e) * smoothstep(0.35, 0.7, fbm(p * 9.0 + float2(o.x, 0.0)));
        float streak = abs(fract(sp * 4.0 + wob * 3.0 + jag) - 0.5);
        float sw = 0.03 + 0.05 * noise(p * 20.0);
        bare = max(bare, rstep(sw, 0.0, streak) * smoothstep(0.4, 0.7, fbm(p * 10.0 + float2(0.0, o.x))) * 0.85);
        bare = max(bare, rstep(0.06, 0.0, r) * 0.9);
    } else if (mode < 1.5) {
        // bullseye: rings pulled up from the centre, jagged where the cloth bunched
        float2 d = p - lp * float2(0.5, 0.30);
        float r = length(d) * (1.0 - 0.06 * pulse);
        float a = atan(d.y, d.x);
        float jag = (fbm(float2(cos(a) * 2.0 + o.x + r * 3.0, sin(a) * 2.0 + o.y)) - 0.5) * smoothstep(0.0, 0.12, r);
        x = r * 16.0 + jag * 1.6 + wob * 0.8 - time * 3.0;
        soft = 0.22 + 0.2 * fbm(p * 5.0 - float2(0.0, o.x));
        float e = abs(fract(x) - 0.5);
        float crink = noise(float2(cos(a) * 30.0 + r * 40.0, sin(a) * 30.0)) * smoothstep(0.0, 0.1, r);
        bare = rstep(0.06 + 0.05 * crink, 0.0, e) * smoothstep(0.3, 0.6, fbm(p * 10.0 + float2(o.y, 0.0)));
        bare = max(bare, rstep(0.04, 0.0, r) * 0.9);
    } else if (mode < 2.5) {
        // crumple: scrunched blotches, pale crackle where the creases kept the dye out
        float2 q = p * 4.0 + 1.4 * (float2(fbm(p * 3.0 + o), fbm(p * 3.0 + float2(5.2 - o.y, 1.3 + o.x))) - 0.5);
        x = fbm(q) * 9.0 + wob;
        soft = 0.14;
        float rid = 1.0 - abs(2.0 * noise(q * 2.5 + float2(3.0, 0.0)) - 1.0);
        float rid2 = 1.0 - abs(2.0 * noise(q * 6.0 + float2(0.0, 2.0)) - 1.0);
        float m = smoothstep(0.3, 0.6, noise(q * 1.3 + float2(9.0, 0.0)));
        bare = max(smoothstep(0.84, 0.96, rid), smoothstep(0.88, 0.98, rid2) * 0.75) * (0.4 + 0.6 * m);
    } else if (mode < 3.5) {
        // ice dye: watercolor blooms where the strongest pigment wins, dark rims, crystal speckle
        float ka = fbm5(p * 3.2 + o);
        float kb = fbm5(p * 3.2 + float2(7.3 - o.y, 2.1 + o.x));
        float kc = fbm5(p * 3.2 + float2(3.9, 9.7 - o.x));
        float mx = max(ka, max(kb, kc));
        float wa = smoothstep(-0.03, 0.03, ka - max(kb, kc));
        float wb = smoothstep(-0.03, 0.03, kb - max(ka, kc));
        float wc = clamp(1.0 - wa - wb, 0.0, 1.0);
        col = float3(c1.rgb) * wa + float3(c2.rgb) * wb + float3(c3.rgb) * wc;
        float sec = min(abs(ka - kb), min(abs(kb - kc), abs(ka - kc)));
        col *= 1.0 - 0.45 * rstep(0.035, 0.0, sec);
        float spk = hash(floor(fc / 2.0) + float2(0.0, 5.0));
        bare = clamp(rstep(0.52, 0.42, mx) + step(0.985, spk) * 0.8, 0.0, 1.0);
        col *= 0.86 + 0.28 * hash(floor(fc / 3.0) + float2(11.0, 0.0));
    } else {
        // chevron: accordion folds, V stripes with smudged seams
        float cx = lp.x * 0.5;
        float v = p.y + abs(p.x - cx + 0.04 * wob) * 1.1;
        x = v * 13.0 + wob * 2.0 + (noise(p * float2(30.0, 6.0)) - 0.5) * 0.4 - time * 3.0;
        soft = 0.25 + 0.25 * fbm(p * float2(4.0, 8.0) + float2(0.0, o.x));
        float e = abs(fract(x) - 0.5);
        bare = rstep(0.06, 0.0, e) * smoothstep(0.35, 0.7, fbm(p * 12.0 + float2(o.x, 0.0)));
        float seam = abs(p.x - cx + 0.04 * wob);
        bare = max(bare, rstep(0.012, 0.0, seam) * 0.8 * smoothstep(0.3, 0.6, noise(float2(p.y * 40.0, 3.0))));
    }
    if (mode < 2.5 || mode > 3.5) col = dye(x, soft);

    col = mix(col, CREAM, 0.65 * bare);
    // the dye sits deep in dark cloth; it soaked in unevenly
    float ink = mix(0.6, 1.0, fbm(p * 4.0 + float2(-o.x, 3.0 + o.y)));
    float depth = clamp(ink * strength * (0.85 + 0.3 * pulse), 0.0, 1.0);
    float g = hash(fc) - 0.5;
    float3 c = mix(CLOTH, col, depth) * (1.0 + 0.10 * g);
    float m = alpha;
    if (fade > 0.0) m *= 1.0 - smoothstep(fade * 0.25, fade, fc.y / size.y);
    return half4(half3(clamp(c, 0.0, 1.0) * m), half(m));
}
"""

/** The tie-dye backdrop: one shader, uniforms set at draw time so a frame never recomposes. */
@RequiresApi(33)
internal class TieDyeBrush {
    private val shader = RuntimeShader(TIE_DYE)

    // a wrong uniform name throws here, inside the caller's runCatching, instead of mid-draw
    init { at(1f, 1f, 0f, 0f, 0f, 0, 0f, 0f, Color.Black, Color.Black, Color.Black) }

    fun at(w: Float, h: Float, time: Float, pulse: Float, strength: Float, mode: Int, fade: Float, alpha: Float, one: Color, two: Color, three: Color): Brush {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("pulse", pulse)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("mode", mode.toFloat())
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("alpha", alpha)
        shader.setColorUniform("c1", one.copy(alpha = 1f).toArgb())
        shader.setColorUniform("c2", two.copy(alpha = 1f).toArgb())
        shader.setColorUniform("c3", three.copy(alpha = 1f).toArgb())
        return ShaderBrush(shader)
    }
}
