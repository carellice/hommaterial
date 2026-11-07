# The widget names its tap handlers by class, and Glance creates them by reflection.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
# Glance finds a widget's ids by its class: if R8 merges the widgets into one class, updating one
# draws its content on all of them (the team widget turned into the devices one).
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
