package com.idtk.jsonparsedemo

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Author: Idtk
 * Time: 2021-11-29
 * Description:
 *      适用于gson解析kotlin data，在数据模型设置了无参数构造的情况下，
 *      如果其中的某个参数类型不为null，而Json返回中明确指定为null，
 *      则会取此参数的默认值，如果没有默认值，则抛出异常
 * 实现方案：
 *      通过无参数构造函数，创建对象，并用此对象的值，默认不可为null，
 *      但是Json中又指定为null的参数进行赋值
 */
class KotlinJsonTypeAdapterFactory : TypeAdapterFactory {

    private val KOTLIN_METADATA = Metadata::class.java

    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {

        val delegate = gson.getDelegateAdapter(this, type)

        val rawType = type.rawType
        if (rawType.isInterface) return null
        if (rawType.isEnum) return null
        // 如果类不是kotlin，就不要使用自定义类型适配器
        if (!rawType.isAnnotationPresent(KOTLIN_METADATA)) return null

        val rawTypeKotlin = rawType.kotlin
        // 无参数构造函数
        val constructor = rawTypeKotlin.primaryConstructor ?: return null
        constructor.isAccessible = true
        // params与value映射
        val paramsValueByName = hashMapOf<String, Any>()
        // 判断是否有空参构造
        val hasNoArgs = rawTypeKotlin.constructors.singleOrNull {
            it.parameters.all(KParameter::isOptional)
        }
        if (hasNoArgs != null) {
            // 无参数构造实例
            val noArgsConstructor = (rawTypeKotlin as KClass<*>).createInstance()
            rawType.declaredFields.forEach {
                it.isAccessible = true
                val value = it.get(noArgsConstructor) ?: return@forEach
                paramsValueByName[it.name] = value
            }
        }


        return object : TypeAdapter<T>() {

            override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)

            override fun read(input: JsonReader): T? {

                if (input.peek() == JsonToken.NULL) {
                    input.nextNull()
                    return null
                }
                val value: T? = delegate.read(input)

                if (value != null) {
                    /**
                     * 在参数不可以为null时，将null转换为默认值，如果没有默认值，则抛出异常
                     */
                    rawTypeKotlin.memberProperties.forEachIndexed { index, it ->
                        if (!it.returnType.isMarkedNullable && it.get(value) == null) {
                            val field = rawType.declaredFields[index]
                            field.isAccessible = true
                            if (paramsValueByName[it.name] != null) {
                                field.set(value, paramsValueByName[it.name])
                            } else {
                                throw JsonParseException(
                                    "Value of non-nullable member " +
                                            "[${it.name}] cannot be null"
                                )
                            }
                        }
                    }
                }
                return value
            }
        }
    }
}