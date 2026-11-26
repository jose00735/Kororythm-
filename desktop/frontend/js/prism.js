// PRISM light show, ported from the Android PrismEngine: a video-feedback
// visualizer. Each frame redraws the previous one slightly zoomed, turned and
// hue-shifted, then adds new light in the middle (rays, wide bands of colour,
// chrome droplets, sparks). Old light keeps being pushed out and recoloured,
// so it smears into rainbow streaks racing out of the centre.

export function prismEngine(canvas) {
  const W = 480, H = 300; // rendered small and scaled up, which also softens it into a glow
  let front = document.createElement("canvas"), back = document.createElement("canvas");
  front.width = back.width = W; front.height = back.height = H;
  let fc = front.getContext("2d"), bc = back.getContext("2d");
  let hue = 40, spin = 0, frame = 0;
  const out = canvas.getContext("2d");
  const col = (h, s, v, a = 1) => `hsla(${((h % 360) + 360) % 360}, ${Math.round(s * 100)}%, ${Math.round(v * 55)}%, ${a})`;

  function step(dt, bass, mid, high) {
    frame++;
    const t = frame / 60, cx = W / 2, cy = H / 2, r = Math.min(W, H) / 2;
    hue = (hue + dt * (25 + 90 * high)) % 360;
    spin += dt * (8 + 40 * mid);

    // 1) previous frame, pushed outward, turned and recoloured
    bc.globalAlpha = 1;
    bc.filter = "none";
    bc.fillStyle = "#000";
    bc.fillRect(0, 0, W, H);
    const zoom = 1.028 + 0.05 * bass;
    bc.save();
    bc.translate(cx, cy);
    bc.rotate(((0.35 + 1.8 * (mid - 0.25) * Math.sin(t * 0.7)) * Math.PI) / 180);
    bc.scale(zoom, zoom * (1.006 + 0.01 * mid));
    bc.translate(-cx, -cy);
    bc.filter = `hue-rotate(${1.6 + 3 * high}deg) saturate(1.07)`;
    bc.globalAlpha = Math.min(0.955, 0.915 + 0.04 * bass);
    bc.drawImage(front, 0, 0);
    bc.restore();
    bc.filter = "none";
    bc.globalAlpha = 1;

    // 2a) broad translucent bands turning slowly
    for (let i = 0; i < 6; i++) {
      const a = (-spin * 0.6 * Math.PI) / 180 + i * (2 * Math.PI / 6);
      const spread = 0.16 + 0.1 * mid, far = r * (0.9 + 0.8 * bass);
      bc.beginPath();
      bc.moveTo(cx, cy);
      bc.lineTo(cx + Math.cos(a - spread) * far, cy + Math.sin(a - spread) * far * 1.4);
      bc.lineTo(cx + Math.cos(a + spread) * far, cy + Math.sin(a + spread) * far * 1.4);
      bc.closePath();
      bc.fillStyle = col(hue + 60 + i * 60, 0.95, 1, (14 + 40 * bass) / 255);
      bc.fill();
    }

    // 2b) star of rays from the middle
    const n = 12 + Math.floor(4 * mid) * 2, len = r * (0.18 + 0.75 * bass);
    bc.lineCap = "round";
    for (let i = 0; i < n; i++) {
      const a = (spin * Math.PI) / 180 + i * (2 * Math.PI / n) + 0.25 * Math.sin(t * 1.3 + i);
      const ex = cx + Math.cos(a) * len, ey = cy + Math.sin(a) * len * Math.min(H / W, 1.6);
      const h = hue + i * (360 / n) * 0.5;
      bc.strokeStyle = col(h, 0.85 + 0.15 * high, 1, Math.min(240, 110 + 140 * bass) / 255);
      bc.lineWidth = r * (0.07 + 0.08 * bass);
      bc.filter = "blur(4px)";
      bc.beginPath(); bc.moveTo(cx, cy); bc.lineTo(ex, ey); bc.stroke();
      bc.filter = "none";
      bc.strokeStyle = col(h, 0.85 + 0.15 * high, 1.2);
      bc.lineWidth = r * (0.012 + 0.02 * bass);
      bc.beginPath(); bc.moveTo(cx, cy); bc.lineTo(ex, ey); bc.stroke();
    }

    // 3) liquid chrome droplets orbiting the centre
    for (let k = 0; k < 7; k++) {
      const ang = t * (0.6 + 0.13 * k) + k * 2.1;
      const orbit = r * (0.08 + 0.18 * mid) * (0.5 + 0.5 * Math.sin(t * 0.9 + k));
      const x = cx + Math.cos(ang) * orbit, y = cy + Math.sin(ang * 1.3) * orbit;
      const rad = r * (0.025 + 0.055 * mid + 0.02 * Math.sin(t * 2 + k));
      if (rad <= 1) continue;
      const g = bc.createRadialGradient(x - rad * 0.35, y - rad * 0.35, 0, x - rad * 0.35, y - rad * 0.35, rad * 1.25);
      g.addColorStop(0, "#fff"); g.addColorStop(0.35, "rgb(205,215,230)"); g.addColorStop(0.8, "rgb(90,110,150)"); g.addColorStop(1, "rgba(0,0,0,0)");
      bc.fillStyle = g;
      bc.beginPath(); bc.arc(x, y, rad, 0, Math.PI * 2); bc.fill();
    }

    // 4) sparks on the highs
    const sparks = Math.floor(4 + 40 * high);
    for (let s = 0; s < sparks; s++) {
      const seed = ((frame * 7919 + s * 104729) % 100000) / 100000;
      const a = seed * 2 * Math.PI, d = r * (0.1 + 0.9 * ((seed * 13.7) % 1));
      bc.fillStyle = col(hue + 180 + seed * 120, 0.4, 1.4, Math.min(255, 120 + 135 * high) / 255);
      bc.beginPath(); bc.arc(cx + Math.cos(a) * d, cy + Math.sin(a) * d, Math.max(1, r * 0.006), 0, Math.PI * 2); bc.fill();
    }

    [front, back] = [back, front];
    [fc, bc] = [bc, fc];
  }

  // Draws the latest frame stretched to fill the visible canvas.
  function present() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== w * devicePixelRatio || canvas.height !== h * devicePixelRatio) {
      canvas.width = Math.max(1, w * devicePixelRatio);
      canvas.height = Math.max(1, h * devicePixelRatio);
    }
    out.imageSmoothingQuality = "high";
    out.drawImage(front, 0, 0, canvas.width, canvas.height);
  }

  return { step, present };
}

/** Bass, mids and highs (0..1) from 64 spectrum bars. */
export function energy(bars) {
  if (!bars?.length) return [0, 0, 0];
  const avg = (a, b) => { let s = 0; for (let i = a; i < b; i++) s += bars[i] || 0; return s / (b - a); };
  const shape = (v) => Math.max(0, Math.min(1, (v - 0.2) / 0.6));
  return [shape(avg(0, 14)), shape(avg(14, 40)), shape(avg(40, 64))];
}
