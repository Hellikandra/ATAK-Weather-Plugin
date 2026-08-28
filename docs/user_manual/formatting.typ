#import "@preview/polylux:0.4.0": *

#let accent_color = rgb(227, 119, 18)

#let maybe-read(path) = context {
  let path-label = label(path)
   let first-time = query((context {}).func()).len() == 0
   if first-time or query(path-label).len() > 0 {
    read(path)
  } else {
    rect(width: 50%, height: 5em, fill: luma(235), stroke: 1pt)[
      #set align(center + horizon)
      Could not find #raw(path)
    ]
  }
}

#let tak-slide(title: [], body) = {
  slide({
    set text(font: "Latin Modern Sans")

    show heading: it => {
      toolbox.register-section(it.body)
      line(length: 100%, stroke: accent_color)
      underline(it.body)
    }

    show heading: set text(accent_color)

    body
  })
}

#let tak-title-slide(
  plugin-name: [], 
  plugin-version: [], 
  platform: [], 
  platform-version: []) = {

  slide({
    set page(
      background: image("titlepage.svg", width: 100%),
    )

    set text(font: "Libre Franklin")

    if (platform != []) {
      place(
        top + left, 
        dy: 80pt,
        dx: 260pt,
        text(size: 30pt, weight: "bold", fill: white, platform)
      )
    }

    set align(center + horizon)
    set text(font: "Latin Modern Sans", tracking: 0pt)
    
    text(size: 32pt, plugin-name)
    text("\n\n")

    text(size: 1em, "Plug-in Version: ")
    text(size: 1em, plugin-version)
    text("\n")
    
    if (platform-version != [] and platform != []) {
      text(platform + " " + platform-version)
      text("\n")
    }

    datetime.today().display("[day] [month repr:long] [year]")
  })
}

#let contents-slide() = {
  set align(center)
  set text(font: "Latin Modern Sans")
  
  set page(header: 
    underline(stroke: accent_color, strong(text(size: 24pt, "Contents")))
  )

  toolbox.side-by-side[][
    #outline(title: "")
  ][]
}

#let userguide(
  plugin-name: [], 
  platform: [], 
  platform-version: [], 
  plugin-version: [], 
  doc) = [

  #set page(paper: "presentation-16-9")

  #set par(justify: true)

  #tak-title-slide(
    plugin-name: plugin-name,
    plugin-version: plugin-version,
    platform-version: platform-version,
    platform: platform
  )

  #contents-slide()

  #set page(
    footer: context [
      #set align(right)
      #set text(10pt)
      #counter(page).display(
        "1 / 1",
        both: true,
      )
    ]
  )

  #doc
]
