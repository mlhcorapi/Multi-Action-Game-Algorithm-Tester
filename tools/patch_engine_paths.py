#!/usr/bin/env python3
"""The one engine change MAGAT needs on top of the thesis tree: resolve the engine's data files
(`terrainProbs.json`, level files) against `-Dtribes.home=<engine dir>` when they are not found in
the working directory, so the engine can be driven from any directory. Applied to
`src/utils/file/IO.java` in a Tribes tree; idempotent.

    python tools/patch_engine_paths.py <tribes engine tree>
"""
import pathlib
import sys

MARK = "    /** MAGAT: resolve a relative data file against -Dtribes.home when it is not in the working directory. */"
RESOLVE = MARK + """
    private static String resolve(String filename) {
        File f = new File(filename);
        if (f.exists() || f.isAbsolute()) return filename;
        String home = System.getProperty("tribes.home");
        if (home != null && !home.isEmpty()) {
            File g = new File(home, filename);
            if (g.exists()) return g.getPath();
        }
        return filename;
    }

"""


def main(argv):
    if len(argv) != 1:
        sys.exit(__doc__)
    io = pathlib.Path(argv[0]).resolve() / "src" / "utils" / "file" / "IO.java"
    text = io.read_text(encoding="utf-8")
    if MARK in text:
        print("already patched")
        return
    anchor = "    public String[] readFile(String filename)\n"
    if anchor not in text:
        sys.exit("unexpected IO.java: readFile signature not found")
    text = text.replace(anchor, RESOLVE + anchor, 1)
    before = text.count("new FileReader(filename)")
    text = text.replace("new FileReader(filename)", "new FileReader(resolve(filename))")
    io.write_text(text, encoding="utf-8")
    print(f"patched {io}: {before} FileReader site(s) now resolve against -Dtribes.home")


if __name__ == "__main__":
    main(sys.argv[1:])
