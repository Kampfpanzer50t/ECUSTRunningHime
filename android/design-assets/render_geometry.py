"""Original pale-blue geometric landscapes, rendered from reproducible 3D scenes.

Run with portable Blender --background --python this-file -- --variant 0.
The reference sheet informs only the cloud/hill/tree vocabulary, not geometry.
No photographs, character art, image-service calls or external models are used.
"""
from pathlib import Path
import argparse
import math
import random
import sys
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).with_name('geometry-renders')
OUT.mkdir(exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument('--variant', type=int, default=0)
parser.add_argument('--preview', action='store_true')
parser.add_argument('--gpu', action='store_true')
args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
index = args.variant
rng = random.Random(613 + index * 117)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)


def rgba(hex_value):
    value = hex_value.lstrip('#')
    rgb = [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return tuple(c / 12.92 if c < .04045 else ((c + .055) / 1.055) ** 2.4 for c in rgb) + (1,)


def material(name, colour, roughness=.42, metal=0, grain=True):
    out = bpy.data.materials.new(name)
    out.diffuse_color = rgba(colour)
    out.use_nodes = True
    bsdf = out.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = rgba(colour)
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Metallic'].default_value = metal
    bsdf.inputs['Coat Weight'].default_value = .12
    bsdf.inputs['Coat Roughness'].default_value = .32
    if grain:
        noise = out.node_tree.nodes.new('ShaderNodeTexNoise')
        noise.inputs['Scale'].default_value = 140
        noise.inputs['Detail'].default_value = 2
        bump = out.node_tree.nodes.new('ShaderNodeBump')
        bump.inputs['Strength'].default_value = .12
        bump.inputs['Distance'].default_value = .018
        out.node_tree.links.new(noise.outputs['Fac'], bump.inputs['Height'])
        out.node_tree.links.new(bump.outputs['Normal'], bsdf.inputs['Normal'])
    return out


ceramic = material('Pearl porcelain', '#F4FAFF', .3)
ice = material('Ice-blue satin', '#BCDDEF', .34)
blue = material('Glacier blue', '#8DBDDD', .4)
slate = material('Soft blue facets', '#A3C9E2', .43)
deep = material('Blue-grey details', '#789FBE', .4)
mist = material('Mist on distant hills', '#CDDFEE', .52)
silver = material('Satin silver-blue water', '#B5D7E9', .23, .18, False)
ground_mat = material('Seamless blue atmosphere', '#DDECF8', .62, grain=False)
ground_shader = ground_mat.node_tree.nodes.get('Principled BSDF')
ground_shader.inputs['Emission Color'].default_value = rgba('#CFE5F7')
ground_shader.inputs['Emission Strength'].default_value = .55
palettes = [ice, slate, blue, mist, ceramic]


def mesh(name, vertices, faces, materials, bevel=0):
    data = bpy.data.meshes.new(name)
    data.from_pydata(vertices, [], faces)
    data.update()
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    for mat in materials:
        obj.data.materials.append(mat)
    if bevel:
        mod = obj.modifiers.new('Softly finished edges', 'BEVEL')
        mod.width = bevel
        mod.segments = 3
        mod.affect = 'EDGES'
        mod = obj.modifiers.new('Weighted corner normals', 'WEIGHTED_NORMAL')
        mod.keep_sharp = True
    return obj


def terrain(name, y_start, y_end, hills, colour_offset):
    # A slightly irregular triangular mesh makes coherent, natural-looking facets.
    columns, rows = 40, 68
    vertices = []
    for j in range(rows + 1):
        y = y_start + (y_end - y_start) * j / rows
        for i in range(columns + 1):
            x = -20 + 40 * i / columns
            px = x + (rng.uniform(-.28, .28) if i not in (0, columns) else 0)
            py = y + (rng.uniform(-.22, .22) if j not in (0, rows) else 0)
            z = .08 + sum(height * math.exp(-(((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2))
                          for cx, cy, rx, ry, height in hills)
            valley_x = .25 + 1.25 * math.sin(py * .33 + index * .6)
            valley = math.exp(-((px - valley_x) / 1.2) ** 6)
            z = z * (1 - valley) + .045 * valley
            vertices.append((px, py, z))
    faces = []
    for j in range(rows):
        for i in range(columns):
            a = j * (columns + 1) + i
            b, c, d = a + 1, a + columns + 1, a + columns + 2
            faces += [(a, b, c), (b, d, c)] if (i + j) % 2 else [(a, b, d), (a, d, c)]
    obj = mesh(name, vertices, faces, [ice, slate, mist, ground_mat], .008)
    for face in obj.data.polygons:
        face.material_index = 3 if face.center.y > 21 else 2 if face.center.y > 16 else (1 if face.center.x < -2 else 0)
    return obj


def mountain(name, x, y, size, height, colour):
    # Irregular nested rings and an offset peak create many believable rock planes.
    n = 9
    verts = []
    phases = [0, .08, -.11, .16]
    radii = [1, .8, .43, .16]
    elevations = [.05, height * .26, height * .62, height * .89]
    for ring in range(4):
        for i in range(n):
            angle = math.tau * i / n + phases[ring]
            jitter = rng.uniform(.82, 1.18)
            verts.append((x + math.cos(angle) * size * radii[ring] * jitter + ring * .09,
                          y + math.sin(angle) * size * .75 * radii[ring] * jitter,
                          elevations[ring] + rng.uniform(-.09, .09)))
    verts.append((x + .24, y + .05, height))
    faces = []
    for ring in range(3):
        for i in range(n):
            a = ring * n + i
            b = ring * n + (i + 1) % n
            c = (ring + 1) * n + i
            d = (ring + 1) * n + (i + 1) % n
            faces += [(a, b, d), (a, d, c)]
    faces += [(3 * n + i, 3 * n + (i + 1) % n, 4 * n) for i in range(n)]
    obj = mesh(name, verts, faces, [colour, ice, mist, ceramic], .035)
    for face in obj.data.polygons:
        face.material_index = (3 if face.center.z > height * .78 else
                               1 if rng.random() < .1 else 0)
    return obj


def sphere(name, xyz, scale, mat, subdivisions=2, smooth=True):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdivisions, radius=1, location=xyz)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    obj.data.materials.append(mat)
    if smooth:
        for poly in obj.data.polygons:
            poly.use_smooth = True
    return obj


def cloud(name, xyz, size):
    x, y, z = xyz
    puffs = []
    for dx, dy, dz, radius in [(-.82, 0, -.1, .6), (0, 0, .18, .93), (.83, .08, -.06, .62),
                                (-.32, -.12, -.35, .65), (.35, -.12, -.32, .65)]:
        puffs.append(sphere(name, (x + dx * size, y + dy * size, z + dz * size),
                            (radius * size, radius * size * .58, radius * size * .72), ceramic, 3))
    bpy.ops.object.select_all(action='DESELECT')
    for puff in puffs:
        puff.select_set(True)
    bpy.context.view_layer.objects.active = puffs[0]
    bpy.ops.object.join()
    obj = bpy.context.object
    obj.name = name
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    mod = obj.modifiers.new('Continuous sculpted cloud', 'REMESH')
    mod.mode = 'VOXEL'
    mod.voxel_size = .055
    bpy.ops.object.modifier_apply(modifier=mod.name)
    mod = obj.modifiers.new('Cloud surface finish', 'SMOOTH')
    mod.factor = 1.15
    mod.iterations = 8
    for poly in obj.data.polygons:
        poly.use_smooth = True
    return obj


def tree(x, y, z, height, shape=0):
    bpy.ops.mesh.primitive_cone_add(vertices=8, radius1=.09 * height, radius2=.07 * height,
                                   depth=.36 * height, location=(x, y, z + .18 * height))
    trunk = bpy.context.object
    trunk.name = 'Blue geometric tree trunk'
    trunk.data.materials.append(deep)
    if shape:
        sphere('Faceted oval canopy', (x, y, z + .9 * height), (.34 * height, .34 * height, .7 * height), ice, 2, False)
    else:
        for layer, (radius, bottom, top) in enumerate([(.39, .2, 1), (.3, .5, 1.2), (.21, .83, 1.38)]):
            bpy.ops.mesh.primitive_cone_add(vertices=6, radius1=height * radius, radius2=.03 * height,
                                           depth=(top - bottom) * height,
                                           location=(x, y, z + (top + bottom) * .5 * height),
                                           rotation=(0, 0, .2))
            obj = bpy.context.object
            obj.name = 'Layered porcelain geometric tree'
            obj.data.materials.append([blue, slate, ice][layer])
            mod = obj.modifiers.new('Rounded tree edges', 'BEVEL')
            mod.width = .035
            mod.segments = 3
            obj.modifiers.new('Tree normals', 'WEIGHTED_NORMAL')


def ribbon():
    # A winding strip picks up genuine reflections rather than painted highlights.
    vertices = []
    n = 180
    for i in range(n):
        y = -35 + i * 55 / (n - 1)
        x = .25 + 1.25 * math.sin(y * .33 + index * .6)
        width = .28 + .15 * (1 - i / n)
        fade = min(1, max(0, (y - 12) / 8))
        width *= 1 - fade
        z = .16 + .035 * math.sin(y * .9) - .22 * fade
        vertices.extend([(x - width, y, z), (x + width, y, z)])
    faces = [(2 * i, 2 * i + 1, 2 * i + 3, 2 * i + 2) for i in range(n - 1)]
    obj = mesh('Abstract satin stream', vertices, faces, [silver])
    mod = obj.modifiers.new('Stream depth', 'SOLIDIFY')
    mod.thickness = .08
    mod = obj.modifiers.new('Soft stream edges', 'BEVEL')
    mod.width = .05
    mod.segments = 3


bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, -.15))
bpy.context.object.name = 'Seamless studio atmosphere'
bpy.context.object.data.materials.append(ground_mat)
shift = [-.5, 1.1, -.9][index]
terrain('Continuous faceted blue hills', -40, 31,
        [(-8, 14, 7, 5, 2.5), (7, 16, 5, 5, 3.3), (-8, 3, 6, 5, 2.7),
         (-11, -4, 5, 4, 2.8), (8, 2, 5, 5, 3.1), (11, -5, 6, 3, 2.1)], 0)
mountain('Glacier sculpted peak', 3.4 + shift, 10.6, [3.0, 2.6, 3.8][index], [6.1, 4.8, 5.2][index], slate)
mountain('Distant porcelain peak', -3.4 + shift, 14, [3.3, 3.6, 3.8][index], [4.3, 6.2, 4.5][index], mist)
mountain('Small frosted peak', 6.6 + shift, 15, 2.7, 4.6, ice)
ribbon()
for x, y, z, h, shape in [(-4.7, -1, 1.0, 1.8, 0), (-5.5, 1, 1.15, 1.45, 1),
                           (5.3, 2, 1.9, 1.6, 0), (6.5, -3, 2.0, 2.25, 1),
                           (-3.6, 7, .75, 1.0, 0), (3.9, 6, 1.4, 1.1, 0)]:
    tree(x + shift * .2, y, z, h, shape)
for _ in range(26):
    x = rng.choice([-1, 1]) * rng.uniform(2.3, 7.7)
    y = rng.uniform(-6, 11)
    sphere('Small faceted pebbles', (x, y, .12), (rng.uniform(.1, .3), rng.uniform(.12, .3), rng.uniform(.12, .28)), ice, 1, False)
cloud('Upper pearl cloud', (-3.3 + shift, 8, 10), 1.16)
cloud('Right suspended cloud', (4.2 + shift * .3, 14, 9.3), 1.25)
cloud('Small distant cloud', (-1.1, 21, 7.5), .64)

# A pale, bevelled halo supplies an abstract focal point in the open upper area.
bpy.ops.mesh.primitive_torus_add(major_segments=96, minor_segments=16, major_radius=1.55,
                                 minor_radius=.075, location=(2.35 - shift, 17, 10.8),
                                 rotation=(math.radians(65), 0, math.radians(-8)))
bpy.context.object.name = 'Fine porcelain horizon halo'
bpy.context.object.data.materials.append(ceramic)
for p in bpy.context.object.data.polygons:
    p.use_smooth = True


def light(name, position, power, size, colour):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy = power
    data.shape = 'DISK'
    data.size = size
    data.color = colour
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.location = position
    obj.rotation_euler = (Vector((0, 6, 0)) - obj.location).to_track_quat('-Z', 'Y').to_euler()


light('Large soft window', (-7, -4, 19), 4200, 11, (1, .98, .95))
light('Blue ambient fill', (9, 8, 15), 2700, 10, (.77, .88, 1))
light('Pearl edge light', (0, 20, 15), 2300, 8, (.88, .95, 1))
scene = bpy.context.scene
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs['Color'].default_value = (.72, .84, 1, 1)
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value = .65
scene.world.mist_settings.start = 27
scene.world.mist_settings.depth = 33
scene.world.mist_settings.falloff = 'QUADRATIC'
scene.view_layers[0].use_pass_mist = True
scene.use_nodes = True
nodes = scene.node_tree.nodes
nodes.clear()
layers = nodes.new('CompositorNodeRLayers')
fog_amount = nodes.new('CompositorNodeMath')
fog_amount.operation = 'MULTIPLY'
fog_amount.inputs[1].default_value = .66
fog = nodes.new('CompositorNodeMixRGB')
fog.blend_type = 'MIX'
fog.inputs[2].default_value = rgba('#D8EAF8')
composite = nodes.new('CompositorNodeComposite')
scene.node_tree.links.new(layers.outputs['Mist'], fog_amount.inputs[0])
scene.node_tree.links.new(fog_amount.outputs[0], fog.inputs[0])
scene.node_tree.links.new(layers.outputs['Image'], fog.inputs[1])
scene.node_tree.links.new(fog.outputs[0], composite.inputs[0])
bpy.ops.object.camera_add(location=(0, -23, 18))
camera = bpy.context.object
camera.rotation_euler = (Vector((0, 7, 4)) - camera.location).to_track_quat('-Z', 'Y').to_euler()
camera.data.type = 'ORTHO'
camera.data.ortho_scale = 29
camera.data.lens = 50
scene.camera = camera
scene.render.engine = 'CYCLES'
scene.cycles.samples = 40 if args.preview else 96
scene.cycles.use_denoising = True
scene.cycles.max_bounces = 7
scene.cycles.diffuse_bounces = 4
scene.cycles.glossy_bounces = 4
scene.render.threads_mode = 'AUTO'
# Try the available GPU, retaining an explicit CPU fallback for portable builds.
try:
    cycles = bpy.context.preferences.addons['cycles'].preferences
    cycles.compute_device_type = 'OPTIX'
    cycles.get_devices()
    gpu = [device for device in cycles.devices if device.type == 'OPTIX']
    if gpu and args.gpu:
        for device in cycles.devices:
            device.use = device in gpu
        scene.cycles.device = 'GPU'
except Exception:
    scene.cycles.device = 'CPU'
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Medium High Contrast'
scene.view_settings.exposure = .45
scene.render.resolution_x = 540 if args.preview else 1080
scene.render.resolution_y = 1080 if args.preview else 2160
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGB'
scene.render.image_settings.color_depth = '8'
scene.render.filepath = str(OUT / f'geometry_{index + 1:02d}{"_preview" if args.preview else ""}.png')
scene.render.film_transparent = False
if not args.preview:
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / f'geometry_{index + 1:02d}.blend'))
try:
    bpy.ops.render.render(write_still=True)
except RuntimeError:
    scene.cycles.device = 'CPU'
    print('GPU unavailable; rendering on CPU', flush=True)
    bpy.ops.render.render(write_still=True)
print('Geometry render complete:', scene.render.filepath, flush=True)
