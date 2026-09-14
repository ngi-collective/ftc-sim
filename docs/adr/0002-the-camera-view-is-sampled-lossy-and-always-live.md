# The camera view is sampled, lossy, and always live

The Dashboard Camera View streams the simulated camera's rendered frames as MJPEG over
HTTP, at the camera's full resolution, at half its frame rate, whether or not an OpMode
is running. Three deliberate departures from "what the detector saw", each stated here
because each is invisible on screen.

Camera *overlays* — annotations for what was detected — are governed by the last section
and are not built yet.

## Why

**MJPEG over HTTP, not the JSON socket.** A browser renders `multipart/x-mixed-replace`
in an `<img>` and decodes every frame itself: no codec, no canvas, no per-frame
JavaScript, no second representation of a frame to keep in step. It is also what a
Limelight serves, so the URL is already familiar to anyone who has debugged vision. The
socket keeps carrying poses, telemetry and detections — the things JSON is good at — and
only advertises where the stream listens, so a reconnect on either side is independent
of the other.

**Full resolution, half the frame rate.** 640x480 is what the SDK's webcam calibrations
are written for and what the detector is handed, and how many pixels across a tag is at
range is what decides whether it can be decoded at all. Downscaling for bandwidth would
hide the one thing the panel exists to show. Frame rate is the cheap axis instead: at
15 fps the view still reads as live and costs half the renders and encodes.

**Live without an OpMode.** The first question anyone asks of a camera view is "can it
see the tag from here", and that question comes *before* the OpMode that depends on the
answer exists. So the session's robot now outlives every run: it is built when the
dashboard starts, rebuilt for each run and after each stop, and it integrates simulated
time while idle, so a teleport settles and a pose keeps flowing between runs.

## Consequences

**On-screen pixels are not the detector's pixels.** JPEG is lossy. Geometry, framing and
apparent tag size all survive, which is what the view is for; per-pixel values do not.
Anything that needs exact pixels — a decode reproduction, a regression fixture — needs a
save-this-frame feature, not a screenshot of a stream.

**The view is a sample, not a record.** The simulated camera runs at 30 fps and a
detector consumes every frame it is given; this panel shows every other one. A frame that
produced a bad detection may never appear. The panel says "sampled view, not every
frame" on its own bar, because a view that looks like a recording invites conclusions
about frames it never showed. Showing every frame a detector received is a different
feature: recording, not streaming.

**Frames exist where real hardware would produce none.** On a Control Hub nothing comes
out of a camera until a `VisionPortal` opens it. Here frames render on demand from the
scene and the robot's pose, with no portal, no streaming state and no OpMode. An OpMode
cannot observe the difference — it still gets frames only through the SDK's own seams —
but anyone comparing the dashboard against a real robot can, and should know it is
intended.

**Encoding lives outside the Android world.** JPEG encoding needs `javax.imageio`, which
`android.jar` does not have, and the Dashboard module compiles against `android.jar` for
the SDK's AARs even though it only ever runs on a desktop JVM. Hence `CameraStream`: a
plain `java-library` that knows nothing about robots, fields or tags and turns pixels
into bytes on a socket. It must never reach an APK.

## Considered and rejected

**Frames on the WebSocket.** Either base64 in JSON, which at 640x480 is about 49 MB/s at
30 fps and impossible, or binary frames, which is merely wasteful: two unrelated kinds of
traffic on one connection, a JavaScript decode and a garbage-collected Blob per frame,
and a reconnect that takes the pose stream down with the video.

**WebRTC.** Right answer for a real camera crossing a real network under bandwidth
pressure. For a 640x480 render on loopback it is a signalling path, an encoder and a
dependency stack bought for nothing.

**A three.js camera viewport in the browser.** Superficially free, since the browser
already renders a 3D field. It is not: the browser has no tag, cluster or game-element
geometry, so it needs new protocol in both directions, and then it is a *second*
renderer that can disagree with the one the detector reads. Two renderers means the
panel can look right while the robot's vision fails, which is the failure this whole
effort exists to prevent.

**Overlays from a desktop AprilTag detector.** `org.openpnp:opencv` ships natives for
every platform the team develops on and its `ArucoDetector` reads `DICT_APRILTAG_36h11`,
so annotating frames server-side would be straightforward. Rejected on fidelity: a
second detector can disagree with the SDK's, and when it does, nobody can tell which one
lied. Overlays must come from the detector the robot actually runs, which means the
Simulated Robot Controller — the build that has the OpenCV and AprilTag natives — acts as
the vision engine, exactly as a Limelight is a camera with the detector on board. Until
that channel exists, any annotation drawn from scene geometry is *truth*, must be
labelled truth, and must never be presented as a detection.
