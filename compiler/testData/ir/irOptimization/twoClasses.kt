// WITH_RUNTIME
import kotlin.reflect.KProperty

class Delegate1(var v: String = "s") {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String {
        val a = 2
        return v
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {

    }
}

class A {
    var a by Delegate1()
}

class B {
    var a by Delegate1()
}