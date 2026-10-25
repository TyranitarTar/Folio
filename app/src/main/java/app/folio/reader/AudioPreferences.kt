package app.folio.reader

import android.content.Context

internal data class AudioPreferences(val backSeconds:Int=10,val forwardSeconds:Int=30,val autoRewind:Boolean=false,
    val rewindSeconds:Int=5,val afterMinutes:Int=1) {
    fun save(context:Context)=context.getSharedPreferences("audiobook-playback",0).edit()
        .putInt("backSeconds",backSeconds).putInt("forwardSeconds",forwardSeconds).putBoolean("autoRewind",autoRewind)
        .putInt("rewindSeconds",rewindSeconds).putInt("afterMinutes",afterMinutes).apply()
    companion object {
        fun load(context:Context):AudioPreferences {
            val p=context.getSharedPreferences("audiobook-playback",0)
            fun choice(key:String,default:Int,allowed:Set<Int>)=p.getInt(key,default).takeIf{it in allowed} ?: default
            return AudioPreferences(choice("backSeconds",10,setOf(5,10,15,30,60)),choice("forwardSeconds",30,setOf(5,10,15,30,60)),
                p.getBoolean("autoRewind",false),choice("rewindSeconds",5,setOf(3,5,10,15)),choice("afterMinutes",1,setOf(1,5,15)))
        }
    }
}
