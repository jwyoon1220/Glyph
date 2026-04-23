package io.github.jwyoon1220.glyph

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.io.File
import java.util.prefs.Preferences

object RecentProjectsManager {
    private val prefs = Preferences.userRoot().node("glyph_recent_projects")
    private const val MAX_RECENTS = 5

    fun addProject(dir: File) {
        val path = dir.absolutePath
        val current = ObjectArrayList(getRecentProjects())
        current.remove(path)
        current.add(0, path)
        
        val trimmed = current.take(MAX_RECENTS)
        prefs.clear()
        
        for ((i, p) in trimmed.withIndex()) {
            prefs.put("recent_$i", p)
        }
    }

    fun getRecentProjects(): List<String> {
        val list = ObjectArrayList<String>()
        for (i in 0 until MAX_RECENTS) {
            val p = prefs.get("recent_$i", null)
            if (p != null) list.add(p)
        }
        // Filter out non-existent
        val valid = list.filter { File(it).exists() }
        if (valid.size != list.size) {
            // Re-sync if some were deleted
            prefs.clear()
            for ((i, p) in valid.withIndex()) {
                prefs.put("recent_$i", p)
            }
        }
        return valid
    }
}
