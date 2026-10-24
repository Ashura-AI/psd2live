//! Modifiers in advanced mode on small rigs whose exact answer is known: a turn sampled at 90° keys, under no
//! parent and under a turned, scaled one; the kinds' formulas at points worked out by hand.

use crate::advanced::{Advanced, MODIFIERS};
use crate::eval::Evaluator;
use crate::rig::*;

const KEYS: [f32; 3] = [-90.0, 0.0, 90.0];
const REST: [f32; 6] = [100.0, 50.0, 140.0, 50.0, 100.0, 90.0];
const PIVOT: [f32; 2] = [100.0, 50.0];

fn constant(values: &[f32]) -> ModifierValue {
    ModifierValue { axes: vec![], values: values.to_vec() }
}

/// A transform turning about [PIVOT] by parameter 0's value, sampled at [KEYS].
fn turn() -> Modifier {
    let rotation = ModifierValue { axes: vec![(0, KEYS.to_vec())], values: KEYS.to_vec() };
    Modifier { kind: ModifierKind::Transform, phase_speed: 0.0, weights: vec![], settings: vec![constant(&[0.0, 0.0]), rotation, constant(&[1.0, 1.0]), constant(&PIVOT)] }
}

fn rotate(p: [f32; 2], about: [f32; 2], degrees: f32) -> [f32; 2] {
    let (s, c) = (degrees as f64).to_radians().sin_cos();
    let (x, y) = ((p[0] - about[0]) as f64, (p[1] - about[1]) as f64);
    [(about[0] as f64 + c * x - s * y) as f32, (about[1] as f64 + s * x + c * y) as f32]
}

/// A parent's map from its space to the canvas: turned by [angle] and scaled by [scale] about [origin].
#[derive(Clone, Copy)]
struct Frame {
    origin: [f32; 2],
    angle: f32,
    scale: f32,
}

impl Frame {
    fn invert(self, p: [f32; 2]) -> [f32; 2] {
        let q = rotate([p[0] - self.origin[0], p[1] - self.origin[1]], [0.0, 0.0], -self.angle);
        [q[0] / self.scale, q[1] / self.scale]
    }
}

/// A mesh with [turn] sampled into its keyforms, under [frame] (a rotation deformer) or none.
fn rig(frame: Option<Frame>) -> Rig {
    let to_local = |p: [f32; 2]| frame.map_or(p, |f| f.invert(p));
    let local: Vec<f32> = REST.chunks(2).flat_map(|p| to_local([p[0], p[1]])).collect();
    let offsets: Vec<Vec<f32>> = KEYS
        .iter()
        .map(|k| REST.chunks(2).zip(local.chunks(2)).flat_map(|(p, l)| {
            let q = to_local(rotate([p[0], p[1]], PIVOT, *k));
            [q[0] - l[0], q[1] - l[1]]
        }).collect())
        .collect();
    let grid = Grid::new(vec![Axis { parameter: 0, keys: KEYS.to_vec() }], offsets.into_iter().enumerate().map(|(i, o)| (vec![i as u16], o)).collect()).unwrap();
    let mesh = Mesh {
        id: "m".into(), name: "m".into(), parent: frame.map(|_| 0), blend: 0, alpha_blend: 0, masked_by: vec![], invert_mask: false, culling: false,
        visible: true, page: -1,
        geometry: Some(Geometry { positions: local, uvs: vec![0.0; 6], indices: vec![0, 1, 2] }),
        offsets: Some(grid), channels: vec![], draw_order: 500.0, opacity: 1.0, multiply: WHITE, screen: BLACK, shapes: vec![],
    };
    let deformers = frame.map(|f| Deformer {
        id: "frame".into(), parent: None, part: None, visible: true, enabled: true, opacity: 1.0, multiply: WHITE, screen: BLACK, channels: vec![],
        kind: DeformerKind::Rotation {
            base_angle: 0.0,
            pivot: Some(Grid::new(vec![], vec![(vec![], Pivot { x: f.origin[0], y: f.origin[1], angle: f.angle, scale: f.scale })]).unwrap()),
            flip_x: false, flip_y: false, shapes: vec![],
        },
    });
    // The canvas-to-parent map: the frame's linear part inverted.
    let to_parent: Vec<f32> = match frame {
        None => [1.0, 0.0, 0.0, 1.0].repeat(3),
        Some(f) => {
            let (ex, ey) = (f.invert([f.origin[0] + 1.0, f.origin[1]]), f.invert([f.origin[0], f.origin[1] + 1.0]));
            [ex[0], ey[0], ex[1], ey[1]].repeat(3)
        }
    };
    let mut rig = Rig {
        canvas: Canvas { width: 512.0, height: 512.0, origin_x: 0.0, origin_y: 0.0, pixels_per_unit: None },
        parameters: vec![Parameter { id: "Turn".into(), name: "Turn".into(), min: -90.0, max: 90.0, default: 0.0, blend: false, repeat: false }],
        deformers: deformers.into_iter().collect(), parts: vec![], meshes: vec![mesh], glues: vec![],
        render: RenderGroup { part: None, draw_order: 500, channels: vec![], composite: None, children: vec![] },
        textures: vec![], physics_fps: 0.0, physics: vec![], clips: vec![], roles: vec![], gui: None, meta: vec![], expressions: vec![], hit_areas: vec![], user_data: vec![], poses: vec![], pose_fade_in: 0.0,
        extensions: Extensions::default(),
    };
    rig.extensions.modifier_stacks.push(ModifierStack { mesh: 0, axes: vec![(0, KEYS.to_vec())], start: constant(&REST), to_parent, modifiers: vec![turn()] });
    rig
}

fn worst(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| (x - y).abs()).fold(0.0, f32::max)
}

fn exact(angle: f32) -> Vec<f32> {
    REST.chunks(2).flat_map(|p| rotate([p[0], p[1]], PIVOT, angle)).collect()
}

#[test]
fn a_sampled_turn_follows_its_arc_between_keys_and_the_bake_at_them() {
    for frame in [None, Some(Frame { origin: [30.0, -20.0], angle: 30.0, scale: 2.0 })] {
        let rig = rig(frame);
        let mut advanced = Advanced::new();
        assert_eq!(advanced.set(&rig, u32::MAX), MODIFIERS);
        let (mut core, mut live) = (Evaluator::new(), Evaluator::new());
        for key in KEYS {
            let baked = core.evaluate(&rig, &[key]).vertices[0].clone();
            assert_eq!(baked, live.evaluate_ext(&rig, &[key], Some(&mut advanced)).vertices[0], "at key {}", key);
        }
        // Midway the bake takes the chord, short of the arc by r(1 - cos 45°) = 11.7 px for r = 40.
        let baked = core.evaluate(&rig, &[45.0]).vertices[0].clone();
        assert!(worst(&baked, &exact(45.0)) > 8.0);
        let corrected = live.evaluate_ext(&rig, &[45.0], Some(&mut advanced)).vertices[0].clone();
        assert!(worst(&corrected, &exact(45.0)) < 1e-3, "{:?} vs {:?}", corrected, exact(45.0));
    }
}

#[test]
fn modifiers_off_evaluate_as_the_core() {
    let rig = rig(Some(Frame { origin: [0.0, 0.0], angle: -15.0, scale: 1.5 }));
    let mut advanced = Advanced::new();
    advanced.set(&rig, MODIFIERS);
    advanced.set(&rig, 0);
    let core = Evaluator::new().evaluate(&rig, &[45.0]).vertices.clone();
    let off = Evaluator::new().evaluate_ext(&rig, &[45.0], Some(&mut advanced)).vertices.clone();
    let bits = |v: &Vec<Vec<f32>>| v.iter().flatten().map(|f| f.to_bits()).collect::<Vec<_>>();
    assert_eq!(bits(&core), bits(&off));
}

#[test]
fn a_wave_with_a_phase_speed_travels_with_time_only() {
    let mut rig = rig(None);
    let wave = Modifier {
        kind: ModifierKind::Wave, phase_speed: 180.0, weights: vec![],
        settings: vec![constant(&[10.0]), constant(&[200.0]), constant(&[0.0]), constant(&[100.0, 0.0]), constant(&[90.0]), constant(&[0.0])],
    };
    rig.extensions.modifier_stacks[0].modifiers = vec![wave];
    let mut advanced = Advanced::new();
    advanced.set(&rig, MODIFIERS);
    let at = |advanced: &mut Advanced| Evaluator::new().evaluate_ext(&rig, &[0.0], Some(advanced)).vertices[0].clone();
    let start = at(&mut advanced);
    assert_eq!(start, Evaluator::new().evaluate(&rig, &[0.0]).vertices[0]);
    // Half a second at 180°/s turns the phase by 90°: the vertex at u = 50 (a quarter wavelength in) goes from a
    // crest's sin(90°) to sin(180°), moving 10 px back across the axis (to the canvas's left, the axis pointing down).
    advanced.advance_time(0.5);
    let moved = at(&mut advanced);
    assert!((moved[0] - (start[0] + 10.0)).abs() < 1e-3 && (moved[1] - start[1]).abs() < 1e-3, "{:?} {:?}", start, moved);
    advanced.set(&rig, MODIFIERS);
    assert_eq!(at(&mut advanced), start);
}

#[test]
fn the_kinds_move_points_as_worked_out_by_hand() {
    let near = |a: &[f32], b: &[f32]| worst(a, b) < 1e-3;
    // A bend up from the origin over 100 px by 90° takes the tip to (R, -R), R = 100 / (π/2).
    let r = (100.0 / std::f64::consts::FRAC_PI_2) as f32;
    let bend = ModifierKind::Bend.deform(&[0.0, -100.0, 0.0, 10.0], &[vec![90.0], vec![0.0, 0.0], vec![-90.0], vec![100.0]], 0.0);
    assert!(near(&bend, &[r, -r, 0.0, 10.0]), "{:?}", bend);
    // A twist of 90° turns its centre's neighbourhood by about 90° and leaves the rim.
    let twist = ModifierKind::Twist.deform(&[0.001, 0.0, 50.0, 0.0], &[vec![90.0], vec![0.0, 0.0], vec![50.0]], 0.0);
    assert!(near(&twist, &[0.0, 0.001, 50.0, 0.0]), "{:?}", twist);
    // A lattice with every control point moved by (5, -3) moves everything by it.
    let lattice = ModifierKind::Lattice.deform(&[20.0, 30.0, -50.0, 400.0], &[vec![0.0, 0.0, 100.0, 100.0], [5.0, -3.0].repeat(16)], 0.0);
    assert!(near(&lattice, &[25.0, 27.0, -45.0, 397.0]), "{:?}", lattice);
    // Every kind is the identity at its defaults.
    let points = [3.0, -7.0, 150.0, 20.0];
    for (kind, values) in [
        (ModifierKind::Transform, vec![vec![0.0, 0.0], vec![0.0], vec![1.0, 1.0], vec![10.0, 10.0]]),
        (ModifierKind::Bend, vec![vec![0.0], vec![0.0, 0.0], vec![-90.0], vec![100.0]]),
        (ModifierKind::Twist, vec![vec![0.0], vec![0.0, 0.0], vec![100.0]]),
        (ModifierKind::Wave, vec![vec![0.0], vec![100.0], vec![0.0], vec![0.0, 0.0], vec![90.0], vec![100.0]]),
        (ModifierKind::Lattice, vec![vec![0.0, 0.0, 100.0, 100.0], vec![0.0; 32]]),
    ] {
        assert_eq!(kind.deform(&points, &values, 0.0), points.to_vec(), "{:?}", kind);
    }
}

#[test]
fn keyed_settings_interpolate_and_hold_past_their_ends() {
    let v = ModifierValue { axes: vec![(0, vec![0.0, 10.0]), (1, vec![5.0])], values: vec![1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0] };
    let at = |a: f32| v.at(4, |p| if p == 0 { a } else { 99.0 });
    assert_eq!(at(-5.0), vec![1.0, 2.0, 3.0, 4.0]);
    assert_eq!(at(5.0), vec![5.5, 11.0, 16.5, 22.0]);
    assert_eq!(at(20.0), vec![10.0, 20.0, 30.0, 40.0]);
}

#[test]
fn a_stack_starts_from_its_mesh_as_its_own_keys_pose_it() {
    // The start is keyed on parameter 0 too: at its last key the mesh has moved 30 px right before the turn.
    let mut rig = rig(None);
    let moved: Vec<f32> = REST.chunks(2).flat_map(|p| [p[0] + 30.0, p[1]]).collect();
    let start = [REST.to_vec(), REST.to_vec(), moved.clone()].concat();
    rig.extensions.modifier_stacks[0].start = ModifierValue { axes: vec![(0, KEYS.to_vec())], values: start };
    let st = &rig.extensions.modifier_stacks[0];
    // Halfway to the last key the start is halfway moved, and the turn acts on it there.
    let shifted: Vec<f32> = REST.chunks(2).flat_map(|p| rotate([p[0] + 15.0, p[1]], PIVOT, 45.0)).collect();
    assert!(worst(&st.canvas_at(|_| 45.0, 0.0), &shifted) < 1e-3);
    for key in KEYS {
        let mut out = vec![0.0; 6];
        st.correct(&[key], 0.0, &mut out);
        assert_eq!(out, vec![0.0; 6], "at key {}", key);
    }
}

#[test]
fn a_path_carries_points_with_its_curve() {
    // A straight path down x = 0 from y = 0 to 90, every control point moved 5 px right: everything near it moves too.
    let path = vec![0.0, 0.0, 0.0, 30.0, 0.0, 60.0, 0.0, 90.0];
    let moved = ModifierKind::Path.deform(&[3.0, 45.0, -10.0, 20.0], &[path.clone(), [5.0, 0.0].repeat(4), vec![0.0]], 0.0);
    assert!(worst(&moved, &[8.0, 45.0, -5.0, 20.0]) < 1e-3, "{:?}", moved);
    // Within a radius the effect fades out across the curve: none at the radius.
    let faded = ModifierKind::Path.deform(&[0.0, 45.0, 20.0, 45.0], &[path.clone(), [5.0, 0.0].repeat(4), vec![20.0]], 0.0);
    assert!(worst(&faded, &[5.0, 45.0, 20.0, 45.0]) < 1e-3, "{:?}", faded);
    assert_eq!(ModifierKind::Path.deform(&[3.0, 4.0], &[path, vec![0.0; 8], vec![0.0]], 0.0), vec![3.0, 4.0]);
}
