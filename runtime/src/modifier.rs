//! Modifiers (`MODS`): each mesh's stack of modifiers as the editor evaluates it, which the core chunks carry
//! sampled into keyforms. Advanced mode adds the stack's departure from its own interpolation over the sampled
//! keys, zero at every key, so between them the keyforms' chords become the modifiers' curves.
//!
//! The kinds' formulas are the editor's (`core/modifier/ModifierKinds.kt`), term for term: canvas pixels (y down),
//! angles in degrees, positive clockwise on the canvas; f64 in between, f32 in and out.

use crate::eval::KEY_SNAP;
use crate::rig::{ModifierKind, ModifierStack, ModifierValue};
use std::f64::consts::PI;

fn radians(degrees: f32) -> f64 {
    degrees as f64 * PI / 180.0
}

impl ModifierKind {
    /// The component count of each setting, in the order `MODS` writes them.
    pub fn sizes(self) -> &'static [usize] {
        match self {
            ModifierKind::Transform => &[2, 1, 2, 2],
            ModifierKind::Bend => &[1, 2, 1, 1],
            ModifierKind::Twist => &[1, 2, 1],
            ModifierKind::Wave => &[1, 1, 1, 2, 1, 1],
            ModifierKind::Lattice => &[4, 32],
            ModifierKind::Path => &[8, 8, 1],
        }
    }

    pub fn from_code(code: u8) -> Option<ModifierKind> {
        Some(match code {
            0 => ModifierKind::Transform,
            1 => ModifierKind::Bend,
            2 => ModifierKind::Twist,
            3 => ModifierKind::Wave,
            4 => ModifierKind::Lattice,
            5 => ModifierKind::Path,
            _ => return None,
        })
    }

    /// [positions] (interleaved canvas x, y) deformed with [values], one per setting; a wave's phase advanced
    /// by [phase_turn] degrees.
    pub fn deform(self, positions: &[f32], values: &[Vec<f32>], phase_turn: f64) -> Vec<f32> {
        let mut out = positions.to_vec();
        let n = positions.len() / 2;
        match self {
            ModifierKind::Transform => {
                let (offset, angle, scale, pivot) = (&values[0], radians(values[1][0]), &values[2], &values[3]);
                let (c, s) = (angle.cos(), angle.sin());
                for i in 0..n {
                    let x = (positions[i * 2] - pivot[0]) as f64 * scale[0] as f64;
                    let y = (positions[i * 2 + 1] - pivot[1]) as f64 * scale[1] as f64;
                    out[i * 2] = (pivot[0] as f64 + c * x - s * y + offset[0] as f64) as f32;
                    out[i * 2 + 1] = (pivot[1] as f64 + s * x + c * y + offset[1] as f64) as f32;
                }
            }
            ModifierKind::Bend => {
                let theta = radians(values[0][0]);
                let origin = &values[1];
                let direction = radians(values[2][0]);
                let length = values[3][0] as f64;
                let (dx, dy) = (direction.cos(), direction.sin());
                // The axis's right on a y-down canvas.
                let (nx, ny) = (-dy, dx);
                if theta.abs() < 1e-9 {
                    return out;
                }
                let radius = length / theta;
                for i in 0..n {
                    let px = positions[i * 2] as f64 - origin[0] as f64;
                    let py = positions[i * 2 + 1] as f64 - origin[1] as f64;
                    let u = px * dx + py * dy;
                    let v = px * nx + py * ny;
                    if u <= 0.0 {
                        continue;
                    }
                    let along = u.min(length);
                    let phi = theta * along / length;
                    let r = radius - v;
                    let mut a = r * phi.sin();
                    let mut b = radius - r * phi.cos();
                    if u > length {
                        a += (u - length) * theta.cos();
                        b += (u - length) * theta.sin();
                    }
                    out[i * 2] = (origin[0] as f64 + a * dx + b * nx) as f32;
                    out[i * 2 + 1] = (origin[1] as f64 + a * dy + b * ny) as f32;
                }
            }
            ModifierKind::Twist => {
                let angle = radians(values[0][0]);
                let center = &values[1];
                let radius = values[2][0] as f64;
                for i in 0..n {
                    let x = positions[i * 2] as f64 - center[0] as f64;
                    let y = positions[i * 2 + 1] as f64 - center[1] as f64;
                    let r = x.hypot(y);
                    if r >= radius {
                        continue;
                    }
                    let k = 1.0 - r / radius;
                    let phi = angle * k * k;
                    let (c, s) = (phi.cos(), phi.sin());
                    out[i * 2] = (center[0] as f64 + c * x - s * y) as f32;
                    out[i * 2 + 1] = (center[1] as f64 + s * x + c * y) as f32;
                }
            }
            ModifierKind::Wave => {
                let amplitude = values[0][0] as f64;
                let wavelength = values[1][0] as f64;
                let phase = if phase_turn == 0.0 { radians(values[2][0]) } else { (values[2][0] as f64 + phase_turn) * PI / 180.0 };
                let origin = &values[3];
                let direction = radians(values[4][0]);
                let ramp = values[5][0] as f64;
                let (dx, dy) = (direction.cos(), direction.sin());
                let (nx, ny) = (-dy, dx);
                if amplitude == 0.0 {
                    return out;
                }
                for i in 0..n {
                    let u = (positions[i * 2] - origin[0]) as f64 * dx + (positions[i * 2 + 1] - origin[1]) as f64 * dy;
                    if u <= 0.0 {
                        continue;
                    }
                    let grow = if ramp <= 0.0 { 1.0 } else { (u / ramp).min(1.0) };
                    let w = amplitude * (2.0 * PI * u / wavelength + phase).sin() * grow;
                    out[i * 2] = (positions[i * 2] as f64 + w * nx) as f32;
                    out[i * 2 + 1] = (positions[i * 2 + 1] as f64 + w * ny) as f32;
                }
            }
            ModifierKind::Lattice => {
                let (bounds, offsets) = (&values[0], &values[1]);
                if offsets.iter().all(|o| *o == 0.0) {
                    return out;
                }
                let (w, h) = ((bounds[2] - bounds[0]) as f64, (bounds[3] - bounds[1]) as f64);
                if w <= 0.0 || h <= 0.0 {
                    return out;
                }
                // The Bernstein weights of the cubic at t.
                let basis = |t: f64| [(1.0 - t) * (1.0 - t) * (1.0 - t), 3.0 * t * (1.0 - t) * (1.0 - t), 3.0 * t * t * (1.0 - t), t * t * t];
                for i in 0..n {
                    let bu = basis(((positions[i * 2] - bounds[0]) as f64 / w).clamp(0.0, 1.0));
                    let bv = basis(((positions[i * 2 + 1] - bounds[1]) as f64 / h).clamp(0.0, 1.0));
                    let (mut x, mut y) = (0.0f64, 0.0f64);
                    for row in 0..4 {
                        for column in 0..4 {
                            let k = bv[row] * bu[column];
                            x += k * offsets[(row * 4 + column) * 2] as f64;
                            y += k * offsets[(row * 4 + column) * 2 + 1] as f64;
                        }
                    }
                    out[i * 2] = (positions[i * 2] as f64 + x) as f32;
                    out[i * 2 + 1] = (positions[i * 2 + 1] as f64 + y) as f32;
                }
            }
            ModifierKind::Path => {
                let (rest, offsets, radius) = (&values[0], &values[1], values[2][0] as f64);
                if offsets.iter().all(|o| *o == 0.0) {
                    return out;
                }
                let moved: Vec<f32> = rest.iter().zip(offsets).map(|(r, o)| r + o).collect();
                let samples: Vec<[f64; 2]> = (0..=PATH_SAMPLES).map(|k| path_point(rest, k as f64 / PATH_SAMPLES as f64)).collect();
                for i in 0..n {
                    let (px, py) = (positions[i * 2] as f64, positions[i * 2 + 1] as f64);
                    // The nearest sample, then a few Newton steps along the curve.
                    let mut nearest = 0;
                    for (k, s) in samples.iter().enumerate() {
                        if (s[0] - px).hypot(s[1] - py) < (samples[nearest][0] - px).hypot(samples[nearest][1] - py) {
                            nearest = k;
                        }
                    }
                    let mut t = nearest as f64 / PATH_SAMPLES as f64;
                    for _ in 0..4 {
                        let (c, d) = (path_point(rest, t), path_tangent(rest, t));
                        let step = ((px - c[0]) * d[0] + (py - c[1]) * d[1]) / path_speed(rest, t).max(1e-6);
                        t = (t + step).clamp(0.0, 1.0);
                    }
                    let (c, tan) = (path_point(rest, t), path_tangent(rest, t));
                    let along = (px - c[0]) * tan[0] + (py - c[1]) * tan[1];
                    let across = (px - c[0]) * -tan[1] + (py - c[1]) * tan[0];
                    let (c2, tan2) = (path_point(&moved, t), path_tangent(&moved, t));
                    // Past an end the point keeps its distance along the end tangent; elsewhere along is (near) zero.
                    let end_along = if t <= 0.0 || t >= 1.0 { along } else { 0.0 };
                    let x = c2[0] + end_along * tan2[0] + across * -tan2[1] - (c[0] + end_along * tan[0] + across * -tan[1]);
                    let y = c2[1] + end_along * tan2[1] + across * tan2[0] - (c[1] + end_along * tan[1] + across * tan[0]);
                    let weight = if radius <= 0.0 {
                        1.0
                    } else {
                        let w = (1.0 - across.abs() / radius).clamp(0.0, 1.0);
                        w * w * (3.0 - 2.0 * w)
                    };
                    out[i * 2] = (px + x * weight) as f32;
                    out[i * 2 + 1] = (py + y * weight) as f32;
                }
            }
        }
        out
    }
}

/// Samples a path is searched over for a point's nearest place on it.
const PATH_SAMPLES: usize = 48;

/// The cubic Bezier through control points [c] (4 x, y pairs) at [t].
fn path_point(c: &[f32], t: f64) -> [f64; 2] {
    let u = 1.0 - t;
    let w = [u * u * u, 3.0 * t * u * u, 3.0 * t * t * u, t * t * t];
    let (mut x, mut y) = (0.0f64, 0.0f64);
    for k in 0..4 {
        x += w[k] * c[k * 2] as f64;
        y += w[k] * c[k * 2 + 1] as f64;
    }
    [x, y]
}

/// The unit tangent at [t]; a degenerate curve points down.
fn path_tangent(c: &[f32], t: f64) -> [f64; 2] {
    let u = 1.0 - t;
    let w = [-3.0 * u * u, 3.0 * u * u - 6.0 * t * u, 6.0 * t * u - 3.0 * t * t, 3.0 * t * t];
    let (mut x, mut y) = (0.0f64, 0.0f64);
    for k in 0..4 {
        x += w[k] * c[k * 2] as f64;
        y += w[k] * c[k * 2 + 1] as f64;
    }
    let l = x.hypot(y);
    if l < 1e-9 { [0.0, 1.0] } else { [x / l, y / l] }
}

/// The curve's speed at [t], pixels per unit of t.
fn path_speed(c: &[f32], t: f64) -> f64 {
    let (lo, hi) = ((t - 1e-4).max(0.0), (t + 1e-4).min(1.0));
    let (a, b) = (path_point(c, lo), path_point(c, hi));
    (b[0] - a[0]).hypot(b[1] - a[1]) / (hi - lo)
}

impl ModifierValue {
    /// This value where [value] gives each parameter's: multilinear between keys, held past the ends, no snap.
    pub fn at(&self, size: usize, value: impl Fn(usize) -> f32) -> Vec<f32> {
        if self.axes.is_empty() {
            return self.values.clone();
        }
        let mut out = vec![0f32; size];
        let mut lower = vec![0usize; self.axes.len()];
        let mut fraction = vec![0f32; self.axes.len()];
        for (a, (parameter, keys)) in self.axes.iter().enumerate() {
            let v = value(*parameter);
            let last = keys.len() - 1;
            if keys.len() == 1 || v <= keys[0] {
                (lower[a], fraction[a]) = (0, 0.0);
            } else if v >= keys[last] {
                (lower[a], fraction[a]) = (last - 1, 1.0);
            } else {
                let i = keys.iter().rposition(|k| *k <= v).unwrap_or(0).min(last - 1);
                (lower[a], fraction[a]) = (i, (v - keys[i]) / (keys[i + 1] - keys[i]));
            }
        }
        for corner in 0..(1usize << self.axes.len()) {
            let (mut weight, mut index, mut stride) = (1f32, 0usize, 1usize);
            for (a, (_, keys)) in self.axes.iter().enumerate() {
                let upper = corner >> a & 1 == 1;
                let k = if keys.len() == 1 { 0 } else { lower[a] + upper as usize };
                weight *= if keys.len() == 1 {
                    if upper { 0.0 } else { 1.0 }
                } else if upper {
                    fraction[a]
                } else {
                    1.0 - fraction[a]
                };
                index += k * stride;
                stride *= keys.len();
            }
            if weight == 0.0 {
                continue;
            }
            for (c, o) in out.iter_mut().enumerate() {
                *o += self.values[index * size + c] * weight;
            }
        }
        out
    }
}

impl ModifierStack {
    /// The stack's canvas positions where [value] gives each parameter's, a wave's phase advanced by its speed
    /// over [time] seconds.
    pub fn canvas_at(&self, value: impl Fn(usize) -> f32, time: f64) -> Vec<f32> {
        let mut positions = self.start.at(self.start.values.len() / self.start.axes.iter().map(|(_, k)| k.len()).product::<usize>(), &value);
        for m in &self.modifiers {
            let values: Vec<Vec<f32>> = m.settings.iter().zip(m.kind.sizes()).map(|(s, size)| s.at(*size, &value)).collect();
            let mut out = m.kind.deform(&positions, &values, m.phase_speed as f64 * time);
            if !m.weights.is_empty() {
                for (i, o) in out.iter_mut().enumerate() {
                    *o = positions[i] + (*o - positions[i]) * m.weights[i / 2];
                }
            }
            positions = out;
        }
        positions
    }

    /// Adds to [out] (the mesh in its parent's space, before its parent's transform) the stack at [values] less
    /// its interpolation over the sampled keys around them at time zero, mapped into the parent's space.
    pub fn correct(&self, values: &[f32], time: f64, out: &mut [f32]) {
        // A value within the snap of a key takes it, as the keyform grids do; each axis brackets it the same way.
        let mut now_values = values.to_vec();
        let spans: Vec<_> = self
            .axes
            .iter()
            .map(|(p, keys)| {
                let raw = values[*p];
                let v = keys.iter().copied().find(|k| (raw - k).abs() < KEY_SNAP).unwrap_or(raw);
                now_values[*p] = v;
                crate::eval::span(keys, v)
            })
            .collect();
        let now = self.canvas_at(|p| now_values[p], time);
        let mut interpolated = vec![0f32; now.len()];
        let mut corner_values = now_values.clone();
        for corner in 0..(1usize << spans.len()) {
            let mut weight = 1.0f32;
            for (i, ((parameter, keys), s)) in self.axes.iter().zip(&spans).enumerate() {
                let upper = corner >> i & 1 == 1;
                if upper && s.hi == s.lo {
                    weight = 0.0;
                    break;
                }
                weight *= if upper { s.t } else { 1.0 - s.t };
                corner_values[*parameter] = keys[if upper { s.hi } else { s.lo }];
            }
            if weight <= 0.0 {
                continue;
            }
            let shape = self.canvas_at(|p| corner_values[p], 0.0);
            for (sum, v) in interpolated.iter_mut().zip(shape) {
                *sum += v * weight;
            }
        }
        let t = &self.to_parent;
        for v in 0..now.len() / 2 {
            let (dx, dy) = (now[v * 2] - interpolated[v * 2], now[v * 2 + 1] - interpolated[v * 2 + 1]);
            if dx == 0.0 && dy == 0.0 {
                continue;
            }
            out[v * 2] += t[v * 4] * dx + t[v * 4 + 1] * dy;
            out[v * 2 + 1] += t[v * 4 + 2] * dx + t[v * 4 + 3] * dy;
        }
    }
}
