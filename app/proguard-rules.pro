# The wire classes are found by name through their generated serializers.
-keep,includedescriptorclasses class com.gridbackgammon.online.**$$serializer { *; }
-keepclassmembers class com.gridbackgammon.online.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
