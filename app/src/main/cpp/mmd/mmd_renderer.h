// OpenGL ES 3.0 renderer for skinned PMX models: toon shading (texture x ramp),
// optional sphere-map add/multiply, and an inverted-hull edge pass. All methods run
// on the GL thread (GLSurfaceView renderer callbacks via mmd_jni).
#pragma once

#include "mmd_engine.h"
#include "mmd_format.h"

#include <GLES3/gl3.h>
#include <vector>

namespace mmd {

class MmdRenderer {
public:
    ~MmdRenderer() { destroy(); }

    // Programs + buffers for [model]; call once on the GL thread after loadModel.
    bool init(const PmxModel& model);
    // Uploads one model texture. Pixels are Android Bitmap order (packed ARGB ints).
    void setTexture(int index, int width, int height, const uint32_t* argbPixels);
    void resize(int width, int height);
    void draw(const MmdEngine& engine);
    void destroy();

private:
    bool buildPrograms();
    void drawMaterial(const MmdEngine& engine, int materialIndex, bool edgePass);

    GLuint toonProgram_ = 0;
    GLuint edgeProgram_ = 0;
    GLuint vao_ = 0;
    GLuint dynamicVbo_ = 0;   // pos3 + normal3, re-uploaded every frame
    GLuint uvVbo_ = 0;        // static
    GLuint edgeScaleVbo_ = 0; // static
    GLuint ibo_ = 0;
    std::vector<GLuint> textures_;
    // Sphere/toon lookups are normalized to [0,1] and sampled right up to both edges, where
    // GL_REPEAT would blend in the opposite edge's texel; those slots clamp instead.
    std::vector<bool> textureClamp_;
    // 1x1 white fallback so uTex/uSphere/uToon (statically referenced by the fragment shader in
    // every branch) always have a complete texture bound, even for materials with none — an
    // unbound sampler is undefined behavior and corrupts the whole frame on some GL drivers.
    GLuint dummyTexture_ = 0;

    int screenWidth_ = 1;
    int screenHeight_ = 1;
    bool ready_ = false;
};

} // namespace mmd
