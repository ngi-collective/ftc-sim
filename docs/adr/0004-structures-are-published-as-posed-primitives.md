# Structures are published as posed primitives

The field's **structures** — the HIVE at field centre, the four FLOWERs on the perimeter — are
described once in Java, in the FTC field frame, as a list of posed primitives: a box or a cylinder
with a size, a pose and a colour. That list goes on the wire in `sim/scene`, and the Dashboard
Field View draws whatever it is sent. The field's **surfaces** — floor tiles and the perimeter —
stay procedural in both renderers and are not published.

The line is: **if physics collides with it, Java owns its geometry and publishes it. If it is
cosmetic and derivable from the three numbers already in `sim/config`, each renderer draws it
itself.**

## Why

**Java has to own it, so the only question was the format.** The camera renderer is pure Java in
`TestFramework` and must work with no dashboard attached at all — instrumented tests and the
Simulated Robot Controller on a device both render frames with nothing listening. So it cannot
consume geometry from the wire. And the browser cannot compute it: the HIVE and FLOWER coordinates
are CAD measurements (`docs/reference/README.md`), and retyping thirty inch-denominated numbers
into TypeScript is the same class of mistake as a mirrored tag — plausible on screen, wrong
everywhere it matters.

**Primitives, not polygons, because primitives are what two of the three consumers want natively.**
The physics world builds colliders from boxes and cylinders. Three.js builds `BoxGeometry` and
`CylinderGeometry` from the same numbers and can then light and shade them, so the field view shows
a HIVE rather than flat paper cut-outs. Only the camera rasteriser wants polygons, because it has
no lighting and one flat-fill primitive (`Surface`), and turning a box into six quads is forty
lines of Java that can be unit-tested on a plain JVM. Publishing tessellated polygons instead
would have made the wire the lowest common denominator: the browser would receive twelve boxes for
a tube it could have drawn as one cylinder, and the payload would carry a hundred-odd quads per
structure for a scene that is republished on every INIT.

**Render geometry and collider geometry are separate emissions from one structure, not one shared
list.** A FLOWER is a tube. Drawn, it is one cylinder. Collided with, it has to be a ring of boxes,
because it must *hold* POLLEN and ode4j has no cylinder-versus-cylinder collider at all — it ships
`CollideCylinderSphere`, `CollideCylinderBox` and `CollideCylinderPlane`, and nothing for two
cylinders. A design that insisted one list serve both would force the cruder of the two on the
other for no reason.

**Tiles and the perimeter are the counter-example that makes the rule worth stating.** They are
already one source in practice: `FieldSurfaces` derives its tiling and its colours from the same
`FieldConfig` numbers `Field.tsx` gets over the wire, at the same pitch, with a documented
exposure gain because the camera has no lights. Moving them onto the wire would add forty quads to
every scene publish to remove twenty lines of arithmetic that cannot drift, since the numbers it
works from are the published ones. A tile is also not something a robot hits — the perimeter's
collider comes from the same `FieldConfig`, not from anything drawn.

## Consequences

**Anything that moves is carried by `sim/bodies`, including structures.** That is already the
pattern for balls: `sim/scene` carries shape and colour once, `sim/bodies` carries a pose per tick,
keyed by a shared id. A HIVE tip therefore arrives the same way a rolling ball does, and the
browser needs no second path for it. This matters because a commanded two-state tip and a
ball-driven hinge are then the same wire — the second is a physics change, not a protocol change.
Structures take ids from the same space as the balls, allocated by the world.

**A structure's colliders are not drawn and its rendering does not collide.** The two will not
match in detail, by design, and a discrepancy between them is not a bug to fix. What must match is
the *dimensions they are both derived from*, which is why those live in one place per structure.

**The camera view and the field view can still disagree about shading, and only shading.** Same
geometry, same colours, different lighting: the camera has none and the browser has some. That is
the existing arrangement for tiles and walls, and ADR-0002's rule is about the world, not the
exposure.
