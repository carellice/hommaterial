# The widget names its tap handlers by class, and Glance creates them by reflection.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
