# The app's own code needs no keep rules: nothing here is reached by reflection, and every string
# that crosses a boundary is written out literally — the `/status` field names, the vendor ids in
# Vendor.id (which is why they are not taken from the enum's own name), the SQLite column names and
# the update manifest's JSON keys.
#
# Entry points come from the manifest, which AGP feeds to R8 on its own, so the Activities, the
# Service and the Application subclass are kept without being named here.
