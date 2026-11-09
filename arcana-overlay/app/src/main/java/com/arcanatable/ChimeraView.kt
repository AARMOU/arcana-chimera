package com.arcanatable

import android.content.SharedPreferences
import org.json.JSONObject

/** Owner, Nov 9: on the open Fold your board and an opponent's sit side by side; turns between them move nothing. */
internal object ChimeraView {
    @Volatile var sideBySide: Boolean = false
}

/**
 * Owner, Nov 9: "when I change the background of the table, I have to change it twice". Studio keeps one design per
 * orientation; the background (picture, fit, dim) is now shared by both: saving it in one saves it for the other.
 */
internal object ChimeraBackdropShare {
    const val KEY = "chimera_table_backdrop_shared"

    fun apply(prefs: SharedPreferences, project: StudioControlProject): StudioControlProject {
        val raw = prefs.getString(KEY, null) ?: return project
        return runCatching {
            val o = JSONObject(raw)
            val name = if (o.has("backdrop") && !o.isNull("backdrop")) o.getString("backdrop").takeIf(StudioBackdrop::validName) else null
            val fit = o.optString("fit", project.backdropFit).takeIf { it in StudioBackdrop.FITS } ?: project.backdropFit
            val dim = o.optDouble("dim", project.backdropDim.toDouble()).toFloat().takeIf { it in 0f..0.9f } ?: project.backdropDim
            project.copy(backdrop = name, backdropFit = fit, backdropDim = dim)
        }.getOrDefault(project)
    }

    fun stage(edit: SharedPreferences.Editor, project: StudioControlProject) {
        edit.putString(KEY, JSONObject().put("backdrop", project.backdrop ?: JSONObject.NULL)
            .put("fit", project.backdropFit).put("dim", project.backdropDim.toDouble()).toString())
    }
}
