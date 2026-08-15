# Shizuku starts this class by name in a privileged app_process process.
-keep class com.wuwa.config.manager.privilege.ShellUserService { public <init>(...); *; }
-keep class com.wuwa.config.manager.privilege.IShellService$Stub { *; }
-keep class com.wuwa.config.manager.privilege.IShellService$Stub$Proxy { *; }

# Keep the Shizuku binder contract and runtime annotations intact while allowing
# the rest of the release app to be renamed and optimized by R8.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep interface com.wuwa.config.manager.privilege.IShellService { *; }
-keep class com.wuwa.config.manager.privilege.IShellService$Default { *; }

# org.json models are parsed explicitly; no reflection-based model keeps are required.
