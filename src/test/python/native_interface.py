"""Native-bar varyings for standalone shader harnesses (exported pairs declare these themselves)."""

EMPTY_PROFILES = """
int dialogmenu_native_row(int id) { return 0; }
ivec3 dialogmenu_native_profile(int id) { return ivec3(-9, 0, 0); }
vec4 dialogmenu_native_box(int id, int box) { return vec4(0); }
"""


def vertex(source, profiles=EMPTY_PROFILES):
    pos = source.index("\n") + 1
    while source[pos:].startswith("#extension"):
        pos = source.index("\n", pos) + 1
    source = source[:pos] + (
        "flat out int dialogmenuNative;\nout vec2 dialogmenuNativeCoord;\n" + profiles
    ) + source[pos:]
    return source.replace("void main() {", "void main() {\n"
                          "    dialogmenuNative = -1;\n    dialogmenuNativeCoord = vec2(0);\n", 1)


def fragment(source, profiles=EMPTY_PROFILES):
    pos = source.index("\n") + 1
    while source[pos:].startswith("#extension"):
        pos = source.index("\n", pos) + 1
    return source[:pos] + (
        "flat in int dialogmenuNative;\nin vec2 dialogmenuNativeCoord;\n" + profiles
    ) + source[pos:]
