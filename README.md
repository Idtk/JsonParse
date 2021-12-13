# Gson与Kotlin data class的NPE问题

# 一、问题

今年项目在线上爆过几次`Gson`与`kotlin data class`的`NullPointerException`，之前没仔细研究，仅仅先对出问题的参数进行了可为的处理，来修复此问题。最近正好有点时间，而且发现此类问题在公司项目中出现的次数不少，所以对此问题的原因进行一下研究，并整理一下处理方案。

## 1、参数没有默认值

先来看看没有构造函数默认值的例子

```kotlin
data class Bean(val id:Int,val name:String)

val json = "{\n  \"id\": 100\n}"
val beanGson = GsonBuilder().create().fromJson(json,Bean::class.java)
Log.i("gson_bean_0","id:${beanGson.id};name:${beanGson.name}")
```

Bean需要的参数有id、name2个，而此时Json中仅有id一个参数，大家猜猜打印会得到什么结果呢？

```kotlin
I/gson_bean_0: id:100;name:null
```

这就有点奇怪了，name不是设置成了不可null的String类型吗？怎么打印出了null？我们先来看下Bean反编译的结果

```java
public final class Bean {
   private final int id;
   @NotNull
   private final String name;

   public final int getId() {
      return this.id;
   }

   @NotNull
   public final String getName() {
      return this.name;
   }

   public Bean(int id, @NotNull String name) {
      Intrinsics.checkNotNullParameter(name, "name");
      super();
      this.id = id;
      this.name = name;
   }
   // 省略 tostring、hashcode、equals等方法
}
```

我们可以看到在`Bean`的构造函数中，对`name`进行了kotlin的`Null-Safely`检查，那么`Gson`解析的时候为什么没有触发`NPE`呢？它是用了什么魔法绕过的呢？这里先挂个钩子1⃣️，等到下面原因探究章节再一起解释。

## 2、所有参数都有默认值

现在我们将`id`与`name`都添加上默认参数，其余设置不变

```kotlin
data class Bean(val id:Int=1,val name:String="idtk")

val json = "{\n  \"id\": 100\n}"
val beanGson = GsonBuilder().create().fromJson(json,Bean::class.java)
Log.i("gson_bean_1","id:${beanGson.id};name:${beanGson.name}")

// log
I/gson_bean_1: id:100;name:idtk
```

虽然Json没有返回具体的name值，但是可以看到参数默认值生效了，现在再来看下反编译之后的Bean类与上面没有默认值时，有什么不同

```java
public final class Bean {
   private final int id;
   @NotNull
   private final String name;

   public final int getId() {
      return this.id;
   }

   @NotNull
   public final String getName() {
      return this.name;
   }

   public Bean(int id, @NotNull String name) {
      Intrinsics.checkNotNullParameter(name, "name");
      super();
      this.id = id;
      this.name = name;
   }

   // $FF: synthetic method
   public Bean(int var1, String var2, int var3, DefaultConstructorMarker var4) {
      if ((var3 & 1) != 0) {
         var1 = 1;
      }

      if ((var3 & 2) != 0) {
         var2 = "idtk";
      }

      this(var1, var2);
   }

   public Bean() {
      this(0, (String)null, 3, (DefaultConstructorMarker)null);
   }

	  // 省略 tostring、hashcode、equals等方法
}
```

这里与无默认值的反编译结果对比，比较明显的就是Bean类多了一个`无参构造函数`，这里需要关注与喜爱，等后面看到源码时，就会明白它的用处。现在再来做另一个实验，如果我在Json中明确指定name为null会怎样呢？

```kotlin
data class Bean(val id:Int=1,val name:String="idtk")

val json = "{\n  \"id\": 100,\n  \"name\": null\n}"
val beanGson = GsonBuilder().create().fromJson(json,Bean::class.java)
Log.i("gson_bean_2","id:${beanGson.id};name:${beanGson.name}")
```

大家可以猜猜会发生什么情况：

1、抛出`NullPointerException`异常

2、打印出name为idtk

3、打印出name为null

```kotlin
I/gson_bean_2: id:100;name:null
```

答应可能超出了部分同学的意料，居然打印出了name为null，这里kotlin的`Null-Safely`检查又没有生效，是什么地方绕过了呢？这里我们挂下第二个钩子2⃣️，等到下面原因探究章节将会得到解释。

## 3、参数部分有默认值

现在我们将部分参数设置默认值，看下情况

```kotlin
data class Bean(val id:Int=1,val name:String)

val json = "{\n  \"id\": 100\n}"
val beanGson = GsonBuilder().create().fromJson(json,Bean::class.java)
Log.i("gson_bean_3","id:${beanGson.id};name:${beanGson.name}")

val json = "{\n  \"id\": 100,\n  \"name\": null\n}"
val beanGson = GsonBuilder().create().fromJson(json,Bean::class.java)
Log.i("gson_bean_4","id:${beanGson.id};name:${beanGson.name}")

// log

I/gson_bean_3: id:100;name:null
I/gson_bean_4: id:100;name:null
```

此种情况与第一种没有默认值的情况类似，在此就不做过多说明了，接下来一起进入`Gson`的源码，探究一下产生这些解析结果的原因吧。

## 二、原因探究

`Gson`的`fromJson`处理方式，一般是根据数据的类型，选择相对应的`TypeAdapter`对数据进行解析，上面的示例为`Bean`对象，最终将走到`ReflectiveTypeAdapterFactory.create`方法中，返回`TypeAdapter`，其中调用了`constructorConstructor.get(type)`方法，这里主要看一下它

```java
public <T> ObjectConstructor<T> get(TypeToken<T> typeToken) {
    final Type type = typeToken.getType();
    final Class<? super T> rawType = typeToken.getRawType();

    // 省略部分代码。。。

    ObjectConstructor<T> defaultConstructor = newDefaultConstructor(rawType);
    if (defaultConstructor != null) {
      return defaultConstructor;
    }

    ObjectConstructor<T> defaultImplementation = newDefaultImplementationConstructor(type, rawType);
    if (defaultImplementation != null) {
      return defaultImplementation;
    }

    // finally try unsafe
    return newUnsafeAllocator(type, rawType);
  }
```

这里有3个方法来创建对象

1、`newDefaultConstructor`方法，通过无参构造函数，尝试创建对象，创建成功则返回对象，否则返回null，进入下一步尝试。

```java
Object[] args = null;
return (T) constructor.newInstance(args);
```

2、`newDefaultImplementationConstructor`方法，通过反射集合框架类型来创建对象，上面的示例显然不是这种情况。

3、兜底的`newUnsafeAllocator`方法，通过`sun.misc.Unsafe`的`allocateInstance`方法构建对象，`Unsafe`类使Java拥有了直接操作内存中数据的能力。如果想要进一步了解`Unsafe`，可以参考美团的文章[《**Java魔法类：Unsafe应用解析**》](https://tech.meituan.com/2019/02/14/talk-about-java-magic-class-unsafe.html)

```java
Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
Field f = unsafeClass.getDeclaredField("theUnsafe");
f.setAccessible(true);
final Object unsafe = f.get(null);
final Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
return new UnsafeAllocator() {
  @Override
  @SuppressWarnings("unchecked")
  public <T> T newInstance(Class<T> c) throws Exception {
    return (T) allocateInstance.invoke(unsafe, c);
  }
};
```

我想看了上面三种构造对象的方法，相信读者对第一章节的两个钩子心里已经有了答案。

### 第一个钩子1⃣️

在这种情况下，data对象并没有无参构造函数，所以在构造对象时，只能使用`Unsafe`的兜底方案，此时直接操作内存获取的对象，自然绕过了2个参数构造函数的`Null-Safely`检查，所以并没有抛出`NPE`，第一章的参数无默认值与部分参数有默认值，都可以归入这种情况。

### 第二个钩子2⃣️

在这种情况下，data对象将会直接适配无参构造函数的方式构建对象，而`Gson`设置对应属性时，又是使用了反射，自然在整个过程中也不会触发kotlin的`Null-Safely`检查，所以并不会抛出`NPE`。

# 三、解决方法

解决上述的Json解析问题，我整理了下面两个方案，可供大家选择。

## 1、选用`moshi`

`moshi` 是 `square` 提供的一个开源库，提供了对 `Kotlin data class`的支持。简单使用如下：

```kotlin
val moshi = Moshi.Builder()
	// 添加kotlin解析的适配器
	.add(KotlinJsonAdapterFactory())
  .build()

val adapter = moshi.adapter(Bean::class.java)
val bean = adapter.fromJson(json)?:return
Log.i("gson_bean_5","${bean.id}:${bean.name}")
```

`moshi`对于Json中明确返回null的参数将会进行校验，如果此参数不可为null，则会抛出`JsonDataException`。对于Json中缺少某个字段，而此字段又没有设置默认值的情况下，则也会抛出`JsonDataException`。

**[moshi的GitHub地址](https://github.com/square/moshi)**

## 2、自定义`Gson`的`TypeAdapterFactory`

`Gson`框架可以通过添加`TypeAdapterFactory`的方式干预Json数据的解析过程，我们可以编写一个自定义的`TypeAdapterFactory`来完成我们对`Kotlin data class`的支持，我们需要达到的目的如下：

- 对于类型不可以为null且设置了默认值的参数，如果Json中缺失此字段或者明确此字段为null，则使用默认值代替
- 对于类型不可为null且未设置默认值的参数，如果Json中缺失此字段或者明确此字段null，则抛出异常
- 对于类型可以为null的参数，不论其是否设置了默认值，返回的Json中缺失此了字段，或者明确此字段为null，都可以正常解析

对以上这些要求，首先需要获取对象的默认值，然后根据1⃣️参数是否为null、2⃣️参数是否可null、3⃣️数据是否有无参构造函数，进行处理步骤如下：

1. 判断是否为kotlin对象，如果不是则跳过，是则继续

    ```kotlin
    private val KOTLIN_METADATA = Metadata::class.java

    // 如果类不是kotlin，就不要使用自定义类型适配器
    if (!rawType.isAnnotationPresent(KOTLIN_METADATA)) return null
    ```

2. 通过无参构造函数，创建出对象，缓存对象的默认值

    ```kotlin
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
    ```

3. 在反序列化时，判断data class的参数是否可以为null，序列化中读取的值是否为null，参数是否有缓存值
    1. 参数可以为null，序列化读取的值为null，则继续
    2. 参数可以为null，序列化读取的值不为null，则继续
    3. 参数不可为null，序列化读取的值不为null，则继续
    4. 参数不可为null，序列化读取的值为null，参数有缓存的默认值，则将参数设置为默认值
    5. 参数不可为null，序列化读取的值为null，参数没有缓存的默认值，则抛出异常

    ```kotlin
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
    ```


### 此方案的缺点

- 现在来思考下，这个方案是否可以完美无缺呢？不知道是否有人注意到了步骤中的第二步，要实行这个方案，必需有一个无参数构造函数，假设`kotlin data class`没有做到这点呢？这时再结合一下`Gson`三个构造函数中的第三个`Unsafe`方案一起思考。此时因为没有无参数构造函数，数据对象将通过`Unsafe`进行对象的创建，数据类型获得了虚拟机赋予的默认值。此时在序列化时基本类型读取到的结果并不会为null，而是会是虚拟机赋予的默认值，从而逃避了检查。

# 四、总结

`Gson`在解析`Kotlin data class`时，如果data没有提供默认的无参数构造函数，`Gson`将通过`Unsafe`方案创建对象，此时将跳过kotlin的`Null-Safely`检查，并且此时对象中数据的值，皆为虚拟机赋予的初始值，而不是我们定义的默认值，所以首先需要给对象提供无参数构造函数。但是即使提供了无参数，如果返回的Json中，明确指定某个参数为null，我们依然无能为力，此时可以接入我上面提供`KotlinJsonTypeAdapterFactory`，它将会检查这个参数是否可以为null，如果不可为null，则使用默认值替换掉null。

此方案并不是完美的，它要求你提供一个有无参数构造函数的`Kotlin data class`，才可以保证不会触发`NullPointerException`。

**[KotlinJsonTypeAdapterFactory仓库地址](https://github.com/Idtk/JsonParse/blob/main/app/src/main/java/com/idtk/jsonparsedemo/KotlinJsonTypeAdapterFactory.kt)**

如果在阅读过程中，有任何疑问与问题，欢迎与我联系。

**博客: [www.idtkm.com](http://www.idtkm.com/)**

**GitHub: [https://github.com/Idtk](https://github.com/Idtk)**

**邮箱: IdtkMa@gmail.com**
