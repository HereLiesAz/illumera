sub onContent()
    c = m.top.itemContent
    m.top.FindNode("poster").uri = c.HDPosterUrl
    m.top.FindNode("title").text = c.title
    ' Continue Watching cards carry their progress (0–1) in `rating`.
    progress = 0
    if c.rating <> invalid and c.rating <> "" then progress = Val(c.rating)
    m.top.FindNode("bar").width = 220 * progress
end sub

sub onFocus()
    if m.top.focusPercent > 0.5 then
        m.top.FindNode("title").color = "0xF2F2F2FF"
    else
        m.top.FindNode("title").color = "0x8A8A8AFF"
    end if
end sub
