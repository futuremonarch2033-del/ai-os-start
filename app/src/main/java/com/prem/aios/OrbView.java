package com.prem.aios;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Arc-reactor orb. Idle: slow coil rotation + very gentle breathing.
 * Speaking: one continuous smooth swell (period SPEAK_PERIOD_S, no double beat),
 * core and ring glow in sync, faint shimmer. Drawn on hardware canvas (gradients only).
 */
public class OrbView extends View {
    private static final float SPEAK_PERIOD_S = 4.5f;
    private static final float IDLE_PERIOD_S = 6.5f;
    private static final int CYAN = 0xFF13D8FF;
    private static final int CYAN_BRIGHT = 0xFF00E5FF;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean speaking = false;
    private float env = 0f;          // 0 idle .. 1 speaking, eased
    private float angle = 0f;        // coil rotation, degrees
    private long last = 0L;
    private long t0 = 0L;
    private float phase = 0f;        // seconds of swell phase
    private float shimmerT = 0f;

    public OrbView(Context c) {
        super(c);
    }

    public void setSpeaking(boolean s) {
        speaking = s;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = MeasureSpec.getSize(w);
        setMeasuredDimension(s, s);
    }

    private static int a(int color, float alpha) {
        int al = Math.max(0, Math.min(255, (int) (alpha * 255f)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    @Override
    protected void onDraw(Canvas cv) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float target = speaking ? 1f : 0f;
        env += (target - env) * Math.min(1f, dt * 2.2f);
        float period = speaking ? SPEAK_PERIOD_S : IDLE_PERIOD_S;
        phase += dt / (env > 0.5f ? SPEAK_PERIOD_S : IDLE_PERIOD_S);
        phase -= (float) Math.floor(phase);
        shimmerT += dt;
        // rotation: ~40s per turn idle, ~30s per turn speaking
        angle += dt * (9f + 3f * env);

        float swell = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase); // 0..1 smooth
        float idleBreath = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase);
        float scale = (1f + 0.03f * (idleBreath - 0.5f)) * (1f - env)
                + (0.86f + 0.35f * swell) * env;
        float glow = (0.55f + 0.1f * idleBreath) * (1f - env) + (0.35f + 0.65f * swell) * env;
        float shimmer = 1f + 0.04f * (float) Math.sin(shimmerT * 7.3f)
                + 0.03f * (float) Math.sin(shimmerT * 11.9f + 1f);
        glow = Math.max(0f, Math.min(1f, glow * shimmer));

        int w = getWidth();
        float cx = w / 2f, cy = getHeight() / 2f;
        float R = w / 2f * 0.80f;   // outer ring radius (leaves room for glow)
        float u = R / 235f;

        // outer halo
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, R * 1.28f,
                new int[]{a(CYAN, 0f), a(CYAN, 0.0f), a(CYAN, 0.22f * glow + 0.06f), a(CYAN, 0f)},
                new float[]{0f, 0.70f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, R * 1.28f, p);
        p.setShader(null);

        // dark disc behind everything
        p.setColor(0xFF04121C);
        cv.drawCircle(cx, cy, 226 * u, p);

        // coil bed (dark teal) between r=150 and r=222
        p.setColor(0xFF0A4558);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(72 * u);
        cv.drawCircle(cx, cy, 186 * u, p);
        p.setStyle(Paint.Style.FILL);

        // 10 coil blocks (rotating)
        cv.save();
        cv.rotate(angle, cx, cy);
        float rin = 152 * u, rout = 222 * u;
        float halfOut = 12.8f, halfIn = 9.6f;
        for (int i = 0; i < 10; i++) {
            float mid = i * 36f - 90f;
            path.reset();
            moveTo(cx, cy, rout, mid - halfOut, true);
            arcTo(cx, cy, rout, mid - halfOut, mid + halfOut);
            lineTo(cx, cy, rin, mid + halfIn);
            arcBack(cx, cy, rin, mid + halfIn, mid - halfIn);
            path.close();
            p.setShader(new RadialGradient(cx, cy, rout,
                    new int[]{a(0xFF0FA8CC, 1f), a(CYAN, 0.88f + 0.12f * glow)},
                    new float[]{rin / rout, 1f}, Shader.TileMode.CLAMP));
            cv.drawPath(path, p);
            p.setShader(null);
        }
        cv.restore();

        // inner dark disc
        p.setShader(new RadialGradient(cx, cy, 150 * u,
                new int[]{0xFF020A12, 0xFF04141F, 0xFF06202E},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, 150 * u, p);
        p.setShader(null);

        // thin rings
        p.setStyle(Paint.Style.STROKE);
        p.setColor(a(CYAN, 0.9f));
        p.setStrokeWidth(2.2f * u);
        cv.drawCircle(cx, cy, 235 * u, p);
        p.setStrokeWidth(1.6f * u);
        p.setColor(a(CYAN, 0.75f));
        cv.drawCircle(cx, cy, 228 * u, p);
        p.setStrokeWidth(3f * u);
        p.setColor(a(CYAN, 0.9f));
        cv.drawCircle(cx, cy, 150 * u, p);
        p.setStrokeWidth(3.5f * u);
        p.setColor(a(CYAN, 0.65f + 0.3f * glow));
        cv.drawCircle(cx, cy, 112 * u, p);

        // core glow (swells with the pulse)
        float coreR = 50f * u * scale;
        float haloR = 118f * u * (0.9f + 0.25f * glow) * (0.9f + 0.1f * scale);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, haloR,
                new int[]{a(0xFFFFFFFF, 0.95f), a(CYAN_BRIGHT, 0.75f * glow + 0.15f),
                        a(CYAN, 0.30f * glow + 0.05f), a(CYAN, 0f)},
                new float[]{0.28f, 0.50f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, haloR, p);
        p.setShader(null);

        // core ring (follows the core)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f * u);
        p.setColor(a(CYAN_BRIGHT, 0.7f + 0.3f * glow));
        cv.drawCircle(cx, cy, 76f * u * (0.97f + 0.05f * (scale - 0.86f) / 0.35f), p);

        // white core
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, coreR,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, a(0xFFB8F4FF, 1f)},
                new float[]{0f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, coreR, p);
        p.setShader(null);

        postInvalidateOnAnimation();
    }

    private static float px(float cx, float r, float deg) {
        return cx + r * (float) Math.cos(Math.toRadians(deg));
    }

    private static float py(float cy, float r, float deg) {
        return cy + r * (float) Math.sin(Math.toRadians(deg));
    }

    private void moveTo(float cx, float cy, float r, float deg, boolean first) {
        path.moveTo(px(cx, r, deg), py(cy, r, deg));
    }

    private void lineTo(float cx, float cy, float r, float deg) {
        path.lineTo(px(cx, r, deg), py(cy, r, deg));
    }

    private void arcTo(float cx, float cy, float r, float from, float to) {
        path.arcTo(cx - r, cy - r, cx + r, cy + r, from, to - from, false);
    }

    private void arcBack(float cx, float cy, float r, float from, float to) {
        path.arcTo(cx - r, cy - r, cx + r, cy + r, from, to - from, false);
    }
}
