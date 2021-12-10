package com.idtk.jsonparsedemo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.gson.GsonBuilder

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /**
         * 演示代码
         */
        val json0 = "{\n  \"id\": 100\n}"
        val json1 = "{\n  \"id\": 100,\n  \"name\": null\n}"
        val beanGson = GsonBuilder()
            .registerTypeAdapterFactory(NonNullTypeAdapterFactory())
            .create()
            .fromJson(json0, Bean::class.java)
        Log.i("gson_bean1", "id:${beanGson.id};name:${beanGson.name}")
    }
}