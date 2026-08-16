#include "mmd_renderer.h"

#include <android/log.h>
#include <cmath>
#include <cstring>

#define RENDER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "MmdRenderer", __VA_ARGS__)

namespace mmd {

namespace {

const char* kToonVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uView;
uniform mat4 uProj;
out vec3 vNormal;
out vec2 vUv;
out vec3 vViewPos;
void main() {
    vec4 viewPos = uView * vec4(aPos, 1.0);
    vViewPos = viewPos.xyz;
    vNormal = mat3(uView) * aNormal;
    vUv = aUv;
    gl_Position = uProj * viewPos;
}
)";

const char* kToonFragmentShader = R"(#version 300 es
precision highp float;
in vec3 vNormal;
in vec2 vUv;
in vec3 vViewPos;
uniform vec4 uDiffuse;
uniform vec3 uSpecular;
uniform float uShininess;
uniform vec3 uAmbient;
uniform vec3 uLightDir;
uniform sampler2D uTex;
uniform int uHasTex;
uniform sampler2D uSphere;
uniform int uSphereMode;
uniform sampler2D uToon;
uniform int uUseDefaultToon;
out vec4 fragColor;
void main() {
    // Back faces of double-sided materials interpolate the front-face normal, which would flip
    // both the toon-ramp lookup and the sphere-map UV on the inside of cloth/hair sheets.
    vec3 N = normalize(vNormal);
    if (!gl_FrontFacing) N = -N;
    float ln = dot(N, -uLightDir);
    float toonT = clamp(ln * 0.5 + 0.5, 0.0, 1.0);
    // PMX toon ramps are authored lit-at-top (v=0 fully lit, v=1 fully shadowed) and carry a
    // tint, not just a level — sample the full RGB at 1-toonT and multiply it in. Materials with
    // no ramp assigned (this model's face/eyes/mouth) fall back to a soft full-range gradient
    // instead of a narrow-band step: a hard step reads as a harsh, differently-styled cel edge
    // next to neighboring toon-textured skin/cloth, which is smoothly graded by comparison.
    // Kept in [0.55, 1.0] so the diffuse color stays readable instead of blowing out (an earlier
    // ambient+toon product exceeded 1.0).
    vec3 toon = uUseDefaultToon == 1
        ? vec3(mix(0.55, 1.0, smoothstep(0.0, 1.0, toonT)))
        : clamp(texture(uToon, vec2(0.5, 1.0 - toonT)).rgb, 0.0, 1.0);
    vec4 color = uDiffuse;
    if (uHasTex == 1) {
        color *= texture(uTex, vUv);
    }
    // Alpha-test, not blend: PMX face/hair decals (iris, eyelashes, the dozens of stacked
    // expression-eye overlays sharing one atlas) are layered by depth, each cut to its shape by
    // an otherwise-opaque texture's alpha channel — not softly translucent. Blending them instead
    // requires exact back-to-front draw order and skips the depth write, so unrelated coincident
    // layers (e.g. every alternate eye expression) all show through at once instead of only the
    // frontmost. A hair tip's soft alpha edge loses its blend gradient to this, but a correctly
    // hidden expression layer matters more than an anti-aliased hair edge.
    if (color.a < 0.5) discard;
    if (uSphereMode != 0) {
        vec2 suv = vec2(N.x * 0.5 + 0.5, 0.5 - N.y * 0.5);
        vec3 sphere = texture(uSphere, suv).rgb;
        if (uSphereMode == 1) color.rgb *= sphere; else color.rgb += sphere;
    }
    vec3 lit = color.rgb * (uAmbient + toon * (1.0 - uAmbient));
    vec3 V = normalize(-vViewPos);
    vec3 H = normalize(V - uLightDir);
    float sp = pow(max(dot(N, H), 0.0), max(uShininess, 1.0));
    lit += uSpecular * sp * step(0.0, ln);
    fragColor = vec4(lit, color.a);
}
)";

const char* kEdgeVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 3) in float aEdgeScale;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uEdgeSize;
void main() {
    vec3 p = aPos + aNormal * (uEdgeSize * aEdgeScale * 0.03);
    gl_Position = uProj * uView * vec4(p, 1.0);
}
)";

const char* kEdgeFragmentShader = R"(#version 300 es
precision highp float;
uniform vec4 uEdgeColor;
out vec4 fragColor;
void main() {
    fragColor = uEdgeColor;
}
)";

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        char log[1024];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        RENDER_LOG("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint linkProgram(const char* vertexSource, const char* fragmentSource) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vertexSource);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (vs == 0 || fs == 0) return 0;
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint status = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        char log[1024];
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        RENDER_LOG("program link failed: %s", log);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

// Column-major 4x4 helpers.
void makePerspective(float* out, float fovYRadians, float aspect, float nearZ, float farZ) {
    const float tanHalf = tanf(fovYRadians * 0.5f);
    memset(out, 0, 16 * sizeof(float));
    out[0] = 1.f / (aspect * tanHalf);
    out[5] = 1.f / tanHalf;
    out[10] = (farZ + nearZ) / (nearZ - farZ);
    out[11] = -1.f;
    out[14] = (2.f * farZ * nearZ) / (nearZ - farZ);
}

void makeLookAt(float* out, const btVector3& eye, const btVector3& center, const btVector3& up) {
    btVector3 f = (center - eye).normalized();
    btVector3 s = f.cross(up).normalized();
    btVector3 u = s.cross(f);
    out[0] = s.x(); out[4] = s.y(); out[8] = s.z();  out[12] = -s.dot(eye);
    out[1] = u.x(); out[5] = u.y(); out[9] = u.z();  out[13] = -u.dot(eye);
    out[2] = -f.x(); out[6] = -f.y(); out[10] = -f.z(); out[14] = f.dot(eye);
    out[3] = 0.f; out[7] = 0.f; out[11] = 0.f; out[15] = 1.f;
}

} // namespace

bool MmdRenderer::buildPrograms() {
    toonProgram_ = linkProgram(kToonVertexShader, kToonFragmentShader);
    edgeProgram_ = linkProgram(kEdgeVertexShader, kEdgeFragmentShader);
    return toonProgram_ != 0 && edgeProgram_ != 0;
}

bool MmdRenderer::init(const PmxModel& model) {
    destroy();
    if (!buildPrograms()) return false;

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &dynamicVbo_);
    glGenBuffers(1, &uvVbo_);
    glGenBuffers(1, &edgeScaleVbo_);
    glGenBuffers(1, &ibo_);

    const size_t vertexCount = model.vertices.size();

    std::vector<float> uvs(vertexCount * 2);
    std::vector<float> edgeScales(vertexCount);
    for (size_t i = 0; i < vertexCount; i++) {
        uvs[i * 2] = model.vertices[i].uv[0];
        uvs[i * 2 + 1] = model.vertices[i].uv[1];
        edgeScales[i] = model.vertices[i].edgeScale;
    }

    glBindVertexArray(vao_);

    glBindBuffer(GL_ARRAY_BUFFER, dynamicVbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(vertexCount * 8 * sizeof(float)), nullptr, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 8 * sizeof(float), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 8 * sizeof(float), reinterpret_cast<void*>(3 * sizeof(float)));

    glBindBuffer(GL_ARRAY_BUFFER, uvVbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(uvs.size() * sizeof(float)), uvs.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);

    glBindBuffer(GL_ARRAY_BUFFER, edgeScaleVbo_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(edgeScales.size() * sizeof(float)), edgeScales.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, sizeof(float), nullptr);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLsizeiptr>(model.indices.size() * sizeof(uint32_t)),
                 model.indices.data(), GL_STATIC_DRAW);

    glBindVertexArray(0);

    textures_.assign(model.textures.size(), 0);

    const int textureCount = static_cast<int>(model.textures.size());
    textureClamp_.assign(model.textures.size(), false);
    for (const PmxMaterial& m : model.materials) {
        if (m.sphereMode != 0 && m.sphereIndex >= 0 && m.sphereIndex < textureCount) {
            textureClamp_[m.sphereIndex] = true;
        }
        if (m.toonFlag == 0 && m.toonIndex >= 0 && m.toonIndex < textureCount) {
            textureClamp_[m.toonIndex] = true;
        }
    }
    // A slot that is also a material texture keeps GL_REPEAT: those UVs may legitimately tile.
    for (const PmxMaterial& m : model.materials) {
        if (m.textureIndex >= 0 && m.textureIndex < textureCount) textureClamp_[m.textureIndex] = false;
    }

    if (dummyTexture_ == 0) {
        const uint8_t white[4] = {255, 255, 255, 255};
        glGenTextures(1, &dummyTexture_);
        glBindTexture(GL_TEXTURE_2D, dummyTexture_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    glEnable(GL_DEPTH_TEST);
    ready_ = true;
    RENDER_LOG("renderer ready: %zu vertices, %zu indices, GL: %s",
               vertexCount, model.indices.size(),
               reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    return true;
}

void MmdRenderer::setTexture(int index, int width, int height, const uint32_t* argbPixels) {
    if (!ready_ || index < 0 || index >= static_cast<int>(textures_.size())) return;

    const size_t pixelCount = static_cast<size_t>(width) * height;
    std::vector<uint8_t> rgba(pixelCount * 4);
    for (size_t i = 0; i < pixelCount; i++) {
        uint32_t c = argbPixels[i];
        rgba[i * 4 + 0] = static_cast<uint8_t>((c >> 16) & 0xFF);
        rgba[i * 4 + 1] = static_cast<uint8_t>((c >> 8) & 0xFF);
        rgba[i * 4 + 2] = static_cast<uint8_t>(c & 0xFF);
        rgba[i * 4 + 3] = static_cast<uint8_t>((c >> 24) & 0xFF);
    }

    if (textures_[index] == 0) glGenTextures(1, &textures_[index]);
    glBindTexture(GL_TEXTURE_2D, textures_[index]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    const GLint wrap = textureClamp_[index] ? GL_CLAMP_TO_EDGE : GL_REPEAT;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
}

void MmdRenderer::resize(int width, int height) {
    screenWidth_ = width;
    screenHeight_ = height;
    // The GL viewport isn't touched anywhere else; without this, the framebuffer keeps
    // whatever viewport was active at context creation, so the model stays pinned to that
    // original size/region (stretched or partly off-screen) after any later resize — rotation,
    // entering/leaving split-screen, or the IME changing the available height.
    glViewport(0, 0, width, height);
}

void MmdRenderer::setFraming(float zoom, float panX, float panY) {
    // Guards the distForHeight/zoom_ division below; a near-zero or negative zoom from a bad
    // gesture value would otherwise push the camera through/behind the model.
    zoom_ = zoom > 0.05f ? zoom : 0.05f;
    panX_ = panX;
    panY_ = panY;
}

void MmdRenderer::drawMaterial(const MmdEngine& engine, int materialIndex, bool edgePass) {
    const PmxModel& model = engine.model();
    const PmxMaterial& m = model.materials[materialIndex];

    if (edgePass) {
        glUseProgram(edgeProgram_);
        glUniform4f(glGetUniformLocation(edgeProgram_, "uEdgeColor"),
                    m.edgeColor[0], m.edgeColor[1], m.edgeColor[2], m.edgeColor[3]);
        glUniform1f(glGetUniformLocation(edgeProgram_, "uEdgeSize"), m.edgeSize);
        glDrawElements(GL_TRIANGLES, m.indexCount, GL_UNSIGNED_INT,
                       reinterpret_cast<void*>(static_cast<uintptr_t>(m.indexOffset) * sizeof(uint32_t)));
        return;
    }

    glUseProgram(toonProgram_);
    glUniform4f(glGetUniformLocation(toonProgram_, "uDiffuse"), m.diffuse[0], m.diffuse[1], m.diffuse[2], m.diffuse[3]);
    glUniform3f(glGetUniformLocation(toonProgram_, "uSpecular"), m.specular[0], m.specular[1], m.specular[2]);
    glUniform1f(glGetUniformLocation(toonProgram_, "uShininess"), m.shininess);
    glUniform3f(glGetUniformLocation(toonProgram_, "uAmbient"), m.ambient[0], m.ambient[1], m.ambient[2]);

    // uTex/uSphere/uToon are statically referenced by every branch of the fragment shader, so
    // each needs a complete texture bound even when unused this draw (an unbound/incomplete
    // sampler is undefined behavior — on this host GL driver it corrupted the entire frame, not
    // just the model, see the mmd rendering fix notes). The dummy 1x1 white texture is the
    // fallback; the uHasTex/uSphereMode/uUseDefaultToon uniforms still gate what the shader does
    // with it.
    int texUnit = 0;
    bool hasTex = m.textureIndex >= 0 && m.textureIndex < static_cast<int>(textures_.size()) && textures_[m.textureIndex] != 0;
    glUniform1i(glGetUniformLocation(toonProgram_, "uHasTex"), hasTex ? 1 : 0);
    glActiveTexture(GL_TEXTURE0 + texUnit);
    glBindTexture(GL_TEXTURE_2D, hasTex ? textures_[m.textureIndex] : dummyTexture_);
    glUniform1i(glGetUniformLocation(toonProgram_, "uTex"), texUnit);
    texUnit++;

    bool hasSphere = m.sphereMode != 0 && m.sphereIndex >= 0 && m.sphereIndex < static_cast<int>(textures_.size()) &&
                     textures_[m.sphereIndex] != 0;
    glUniform1i(glGetUniformLocation(toonProgram_, "uSphereMode"), hasSphere ? m.sphereMode : 0);
    glActiveTexture(GL_TEXTURE0 + texUnit);
    glBindTexture(GL_TEXTURE_2D, hasSphere ? textures_[m.sphereIndex] : dummyTexture_);
    glUniform1i(glGetUniformLocation(toonProgram_, "uSphere"), texUnit);
    texUnit++;

    // File-referenced toon ramps use the texture; shared toons fall back to the procedural
    // cel step inside the shader (close enough to MMD's toon01..toon10 set).
    bool hasToonTex = m.toonFlag == 0 && m.toonIndex >= 0 && m.toonIndex < static_cast<int>(textures_.size()) &&
                      textures_[m.toonIndex] != 0;
    glUniform1i(glGetUniformLocation(toonProgram_, "uUseDefaultToon"), hasToonTex ? 0 : 1);
    glActiveTexture(GL_TEXTURE0 + texUnit);
    glBindTexture(GL_TEXTURE_2D, hasToonTex ? textures_[m.toonIndex] : dummyTexture_);
    glUniform1i(glGetUniformLocation(toonProgram_, "uToon"), texUnit);
    texUnit++;

    glDrawElements(GL_TRIANGLES, m.indexCount, GL_UNSIGNED_INT,
                   reinterpret_cast<void*>(static_cast<uintptr_t>(m.indexOffset) * sizeof(uint32_t)));
}

void MmdRenderer::draw(const MmdEngine& engine) {
    if (!ready_ || !engine.hasModel()) return;
    const PmxModel& model = engine.model();

    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // Camera: framed on the model's bind-pose bounds, on the -Z side (PMX models face -Z).
    // Distance must satisfy both the vertical extent (height) and the horizontal one (width —
    // arms reach far past shoulder width on an unposed/T-pose bind skeleton), each against its
    // own half-angle: a portrait screen's horizontal FOV is narrower than its vertical one
    // (tan(fovX/2) = aspect * tan(fovY/2)), so on a tall phone the arm span is usually the
    // tighter constraint, not the height alone. zoom_/panX_/panY_ (see setFraming) then crop
    // that base framing to whatever range the user picked in the GUI: zoom_ scales the distance
    // (>1 moves in) and pan shifts the look-at point in the same world units as the bounds, both
    // applied to eye and center alike so the camera translates in place rather than rotating.
    const float fov = 30.f * 3.14159265358979323846f / 180.f;
    const float aspect = static_cast<float>(screenWidth_) / static_cast<float>(screenHeight_);
    const float tanHalfFov = tanf(fov * 0.5f);
    const float distForHeight = engine.boundsHeight() / (2.f * tanHalfFov);
    const float distForWidth = engine.boundsWidth() / (2.f * aspect * tanHalfFov);
    const float dist = btMax(distForHeight, distForWidth) * 1.15f / zoom_; // small margin around the subject
    btVector3 center = engine.boundsCenter() +
                        btVector3(panX_ * engine.boundsWidth(), panY_ * engine.boundsHeight(), 0.f);
    btVector3 eye = center + btVector3(0, 0, -dist);
    float view[16], proj[16];
    makeLookAt(view, eye, center, btVector3(0, 1, 0));
    makePerspective(proj, fov, aspect, dist * 0.05f, dist * 20.f);

    // Upload this frame's skinned vertices.
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, dynamicVbo_);
    // Orphan (glBufferData, not glBufferSubData): this buffer is rewritten every frame while the
    // GPU may still be reading last frame's contents for its draw calls. glBufferSubData writes
    // into the live buffer and relies on the driver to stall/sync correctly; glBufferData with a
    // fresh allocation lets the driver detach a new buffer instead, avoiding that read/write race
    // (traced whole-frame corruption on one host GL driver to this hazard).
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(engine.vertexCount() * 8 * sizeof(float)),
                 engine.skinnedVertices(), GL_DYNAMIC_DRAW);

    btVector3 lightWorld = btVector3(-0.5f, -1.f, 0.5f).normalized();
    btVector3 lightView = btVector3(
        view[0] * lightWorld.x() + view[4] * lightWorld.y() + view[8] * lightWorld.z(),
        view[1] * lightWorld.x() + view[5] * lightWorld.y() + view[9] * lightWorld.z(),
        view[2] * lightWorld.x() + view[6] * lightWorld.y() + view[10] * lightWorld.z());

    auto setMatrices = [&](GLuint program) {
        glUseProgram(program);
        glUniformMatrix4fv(glGetUniformLocation(program, "uView"), 1, GL_FALSE, view);
        glUniformMatrix4fv(glGetUniformLocation(program, "uProj"), 1, GL_FALSE, proj);
        if (program == toonProgram_) {
            glUniform3f(glGetUniformLocation(program, "uLightDir"), lightView.x(), lightView.y(), lightView.z());
        }
    };

    setMatrices(edgeProgram_);
    setMatrices(toonProgram_);

    // 1) Edge pass: expanded back faces in the edge color, drawn first so the body covers them.
    glDepthMask(GL_TRUE);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_FRONT);
    setMatrices(edgeProgram_);
    for (size_t i = 0; i < model.materials.size(); i++) {
        if (model.materials[i].flags & PmxMaterial::FLAG_EDGE) {
            drawMaterial(engine, static_cast<int>(i), true);
        }
    }

    // 2) Body pass: every material, single pass, depth write always on. The shader's alpha
    // test (discard) handles cutouts (hair, eyelashes, stacked expression-eye decals) directly,
    // so unlike a separate blended pass this keeps normal depth occlusion between materials —
    // required for decal layers (e.g. iris in front of sclera) to hide each other correctly.
    // The edge-scale attribute (location 3) is only consumed by the edge program; leaving it
    // enabled for toon draws trips some host drivers (blank frame), so it is toggled per pass.
    glDisableVertexAttribArray(3);
    glCullFace(GL_BACK);
    setMatrices(toonProgram_);
    for (size_t i = 0; i < model.materials.size(); i++) {
        const PmxMaterial& m = model.materials[i];
        if (m.flags & PmxMaterial::FLAG_DOUBLE_SIDED) glDisable(GL_CULL_FACE); else glEnable(GL_CULL_FACE);
        drawMaterial(engine, static_cast<int>(i), false);
    }
    glEnableVertexAttribArray(3);
    glDisable(GL_CULL_FACE);
    glBindVertexArray(0);
}

void MmdRenderer::destroy() {
    if (vao_ != 0) { glDeleteVertexArrays(1, &vao_); vao_ = 0; }
    GLuint buffers[4] = {dynamicVbo_, uvVbo_, edgeScaleVbo_, ibo_};
    glDeleteBuffers(4, buffers);
    dynamicVbo_ = uvVbo_ = edgeScaleVbo_ = ibo_ = 0;
    for (GLuint tex : textures_) {
        if (tex != 0) glDeleteTextures(1, &tex);
    }
    textures_.clear();
    textureClamp_.clear();
    if (dummyTexture_ != 0) { glDeleteTextures(1, &dummyTexture_); dummyTexture_ = 0; }
    if (toonProgram_ != 0) { glDeleteProgram(toonProgram_); toonProgram_ = 0; }
    if (edgeProgram_ != 0) { glDeleteProgram(edgeProgram_); edgeProgram_ = 0; }
    ready_ = false;
}

} // namespace mmd
