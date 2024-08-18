plugins {
    id("dev.nokee.c-library")
}

version = "0.1"

library {
    cSources.from(fileTree("chelper") {
        include("*.c")
    })
    privateHeaders.from(fileTree("chelper"))
    publicHeaders.from(fileTree("chelper"))
    targetLinkages.add(linkages.static)
}
