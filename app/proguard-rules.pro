# R8 rules for the TCP Transport plugin.
#
# The plugin uses no reflection or serialization, and its Android components
# (ScanReceiver, PluginControlReceiver, TcpService) are declared in the manifest,
# so AGP auto-generates keep rules for them. Compose, coroutines and AndroidX ship
# their own consumer ProGuard rules. Hence no manual keeps are needed here.
#
# Add app-specific -keep rules below only if a future feature introduces reflection
# (e.g. Gson/Moshi models, dynamically loaded classes).
