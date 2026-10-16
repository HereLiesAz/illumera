' Run with scripts/test.mjs (the brs interpreter). Mirrors web/test/parser.test.ts and sorting.test.ts.

sub Main()
    m.failures = 0

    Expect("explicit beats words", QualityOf("Movie 1080p HD"), "1080p")
    Expect("uhd", QualityOf("UHD remux"), "4k")
    Expect("hdcam", QualityOf("HDCAM"), "cam")
    Expect("unknown", QualityOf("nothing"), "unknown")

    s = ParseStream({ name: "Torrentio" + Chr(10) + "4k", title: "Movie.1080p.WEB", behaviorHints: { filename: "movie.mkv" } })
    Expect("first field with a quality", s.quality, "1080p")

    Expect("size 1024s", SizeBytes("1.5 GB"), 1.5 * 1073741824#)
    Expect("size over 2 GB", SizeBytes("9 GB"), 9 * 1073741824#)
    Expect("GiB ignored", SizeBytes("2 GiB"), invalid)
    Expect("videoSize wins", ParseStream({ title: "9 GB", behaviorHints: { videoSize: 42 } }).sizeBytes, 42)

    Expect("seeds emoji", Seeds("👤 1,234"), 1234)
    Expect("seeds word", Seeds("Seeds: 12"), 12)
    Expect("S02E05 is not seeds", Seeds("Show S02E05"), invalid)

    f = Formats("x265 HDR10+ DDP5.1 Atmos")
    Expect("formats", f.hevc = true and f.hdr = true and f.dolby = true and f.dv = invalid, true)

    out = Titles(SortStreams([St("480p 1 GB"), St("CAM 1 GB"), St("1080p 1 GB")], "movie", ["a"]))
    Expect("SD and CAM hidden", out, "1080p 1 GB")
    out = Titles(SortStreams([St("2160p 20 GB"), St("720p 3 GB"), St("1080p")], "movie", ["a"]))
    Expect("target size first, unsized last", out, "720p 3 GB|2160p 20 GB|1080p")
    out = Titles(SortStreams([St("1080p 3 GB"), St("1080p 700 MB")], "series", ["a"]))
    Expect("episode target", out, "1080p 700 MB|1080p 3 GB")
    prefs = DefaultSortPrefs()
    prefs.movieTargetSizeMb = 0
    prefs.minimumSeeds = 0
    list = [{ title: "720p", addonBase: "b" }, { title: "1080p", addonBase: "b" }, { title: "720p", addonBase: "a" }]
    sorted = SortStreams(list, "movie", ["a", "b"], prefs)
    got = []
    for each x in sorted
        got.Push(x.addonBase + x.title)
    end for
    Expect("addon order then quality", got.Join("|"), "a720p|b1080p|b720p")
    prefs = DefaultSortPrefs()
    prefs.excludePhrases = ["hindi"]
    prefs.maxSizeGb = 5
    out = Titles(SortStreams([St("1080p Hindi"), St("1080p 10 GB"), St("1080p ok")], "movie", ["a"], prefs))
    Expect("phrase and max size", out, "1080p ok")

    Expect("public http upgraded", TransportUrl("http://addon.example/x/manifest.json"), "https://addon.example/x")
    Expect("default port dropped", UpgradeUrl("http://addon.example:80/x"), "https://addon.example/x")
    Expect("port kept", UpgradeUrl("http://addon.example:7000/x"), "https://addon.example:7000/x")
    Expect("stremio scheme", TransportUrl("stremio://addon.example/manifest.json"), "https://addon.example")
    Expect("https untouched", UpgradeUrl("https://addon.example/x"), "https://addon.example/x")
    Expect("lan ip stays http", UpgradeUrl("http://192.168.1.5:11470/x"), "http://192.168.1.5:11470/x")
    Expect("172.16 stays http", UpgradeUrl("http://172.16.0.9/x"), "http://172.16.0.9/x")
    Expect("172.32 upgraded", UpgradeUrl("http://172.32.0.1/x"), "https://172.32.0.1/x")
    Expect("localhost stays http", UpgradeUrl("http://localhost:8090"), "http://localhost:8090")
    Expect(".local stays http", UpgradeUrl("http://nas.local:8080/x"), "http://nas.local:8080/x")
    Expect("ipv6 loopback stays http", UpgradeUrl("http://[::1]:11470/x"), "http://[::1]:11470/x")
    Expect("public ipv6 upgraded", UpgradeUrl("http://[2001:db8::1]/x"), "https://[2001:db8::1]/x")
    Expect("look-alike 10. host upgraded", UpgradeUrl("http://10.example.com/x"), "https://10.example.com/x")
    Expect("look-alike 127. host upgraded", UpgradeUrl("http://127.evil.com/x"), "https://127.evil.com/x")
    Expect("octet over 255 upgraded", UpgradeUrl("http://300.1.1.1/x"), "https://300.1.1.1/x")
    Expect("types listed", AcceptsType({ manifest: { types: ["movie"] } }, "series"), false)
    Expect("types match", AcceptsType({ manifest: { types: ["movie"] } }, "movie"), true)
    Expect("no types accepts all", AcceptsType({ manifest: {} }, "series"), true)

    if m.failures = 0 then print "ALL PASSED" else print m.failures.ToStr() + " FAILED"
end sub

function St(title as String) as Object
    return { title: title, addonBase: "a" }
end function

function Titles(list as Object) as String
    out = []
    for each s in list
        out.Push(s.title)
    end for
    return out.Join("|")
end function

sub Expect(name as String, actual as Dynamic, expected as Dynamic)
    same = false
    if actual = invalid or expected = invalid then
        same = (actual = invalid) = (expected = invalid)
    else
        same = actual = expected
    end if
    if not same then
        m.failures = m.failures + 1
        print "FAIL " + name + ": got " + FormatJson(actual) + ", expected " + FormatJson(expected)
    end if
end sub
