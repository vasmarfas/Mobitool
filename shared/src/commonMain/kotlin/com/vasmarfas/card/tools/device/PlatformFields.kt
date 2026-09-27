package com.vasmarfas.card.tools.device

import androidx.compose.runtime.Composable
import com.vasmarfas.card.core.SensorType
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

// platforms report these in English, as their APIs name them. Anything not listed is shown as it came
private val fieldNames: Map<String, StringResource> = mapOf(
    "Brand" to Res.string.platform_field_brand,
    "Device" to Res.string.platform_field_device,
    "Board" to Res.string.platform_field_board,
    "Hardware" to Res.string.platform_field_hardware,
    "Security patch" to Res.string.platform_field_security_patch,
    "Build" to Res.string.platform_field_build,
    "Status" to Res.string.platform_field_status,
    "Plugged" to Res.string.platform_field_plugged,
    "Health" to Res.string.platform_field_health,
    "Temperature" to Res.string.platform_field_temperature,
    "Voltage" to Res.string.platform_field_voltage,
    "Design voltage" to Res.string.platform_field_design_voltage,
    "Technology" to Res.string.platform_field_technology,
    "Current now" to Res.string.platform_field_current_now,
    "Current" to Res.string.platform_field_current,
    "Charge counter" to Res.string.platform_field_charge_counter,
    "Energy" to Res.string.platform_field_energy,
    "Cycle count" to Res.string.platform_field_cycle_count,
    "Time to full" to Res.string.platform_field_time_to_full,
    "Time to empty" to Res.string.platform_field_time_to_empty,
    "Estimated runtime" to Res.string.platform_field_estimated_runtime,
    "Manufacturer" to Res.string.platform_field_manufacturer,
    "Model" to Res.string.platform_field_model,
    "Name" to Res.string.platform_field_name,
    "Resolution" to Res.string.platform_field_resolution,
    "Density" to Res.string.platform_field_density,
    "Physical DPI" to Res.string.platform_field_physical_dpi,
    "Logical DPI" to Res.string.platform_field_logical_dpi,
    "Diagonal" to Res.string.platform_field_diagonal,
    "Refresh rate" to Res.string.platform_field_refresh_rate,
    "Supported modes" to Res.string.platform_field_supported_modes,
    "Font scale" to Res.string.platform_field_font_scale,
    "Scale" to Res.string.platform_field_scale,
    "Brightness" to Res.string.platform_field_brightness,
    "Points" to Res.string.platform_field_points,
    "Pixels" to Res.string.platform_field_pixels,
    "Display" to Res.string.platform_field_display,
    "Screen" to Res.string.platform_field_screen,
    "Device pixel ratio" to Res.string.platform_field_device_pixel_ratio,
    "Color depth" to Res.string.platform_field_color_depth,
    "Viewport" to Res.string.platform_field_viewport,
    "Orientation" to Res.string.platform_field_orientation,
    "Platform" to Res.string.platform_field_platform,
    "CPU cores" to Res.string.platform_field_cpu_cores,
    "CPU threads" to Res.string.platform_field_cpu_threads,
    "Memory" to Res.string.platform_field_memory,
    "Device memory" to Res.string.platform_field_device_memory,
    "Max heap" to Res.string.platform_field_max_heap,
    "User" to Res.string.platform_field_user,
    "Transport" to Res.string.platform_field_transport,
    "Downstream" to Res.string.platform_field_downstream,
    "Upstream" to Res.string.platform_field_upstream,
    "Metered" to Res.string.platform_field_metered,
    "Validated" to Res.string.platform_field_validated,
    "Interface" to Res.string.platform_field_interface,
    "Addresses" to Res.string.platform_field_addresses,
    "Gateway" to Res.string.platform_field_gateway,
    "Domains" to Res.string.platform_field_domains,
    "Link speed" to Res.string.platform_field_link_speed,
    "Frequency" to Res.string.platform_field_frequency,
    "Wi-Fi standard" to Res.string.platform_field_wifi_standard,
    "Wi-Fi enabled" to Res.string.platform_field_wifi_enabled,
    "5 GHz supported" to Res.string.platform_field_5ghz,
    "6 GHz supported" to Res.string.platform_field_6ghz,
    "Raw" to Res.string.platform_field_raw,
)

private val fieldValues: Map<String, StringResource> = mapOf(
    "charging" to Res.string.platform_state_charging,
    "discharging" to Res.string.platform_state_discharging,
    "full" to Res.string.platform_state_full,
    "fully charged" to Res.string.platform_state_fully_charged,
    "not charging" to Res.string.platform_state_not_charging,
    "on AC" to Res.string.platform_state_on_ac,
    "low" to Res.string.platform_state_low,
    "critical" to Res.string.platform_state_critical,
    "unknown" to Res.string.platform_state_unknown,
    "wireless" to Res.string.platform_state_wireless,
    "no" to Res.string.platform_state_no,
    "good" to Res.string.platform_state_good,
    "overheat" to Res.string.platform_state_overheat,
    "dead" to Res.string.platform_state_dead,
    "over voltage" to Res.string.platform_state_over_voltage,
    "cold" to Res.string.platform_state_cold,
    "true" to Res.string.platform_state_yes,
    "false" to Res.string.platform_state_no,
    "n/a" to Res.string.platform_state_n_a,
    "portrait-primary" to Res.string.platform_state_portrait,
    "portrait-secondary" to Res.string.platform_portrait_upside_down,
    "landscape-primary" to Res.string.platform_state_landscape,
    "landscape-secondary" to Res.string.platform_landscape_upside_down,
    "Cellular" to Res.string.platform_state_cellular,
    "other" to Res.string.platform_state_other,
    "charging (high)" to Res.string.platform_state_charging_high,
    "charging (low)" to Res.string.platform_state_charging_low,
    "charging (critical)" to Res.string.platform_state_charging_critical,
)

private val sensorNames: Map<SensorType, StringResource> = mapOf(
    SensorType.ACCELEROMETER to Res.string.sensor_name_accelerometer,
    SensorType.GYROSCOPE to Res.string.sensor_name_gyroscope,
    SensorType.MAGNETOMETER to Res.string.sensor_name_magnetometer,
    SensorType.LIGHT to Res.string.sensor_name_light,
    SensorType.PRESSURE to Res.string.sensor_name_pressure,
    SensorType.PROXIMITY to Res.string.sensor_name_proximity,
    SensorType.ORIENTATION to Res.string.sensor_name_orientation,
    SensorType.STEP_COUNTER to Res.string.sensor_name_step_counter,
    SensorType.HUMIDITY to Res.string.sensor_name_humidity,
    SensorType.TEMPERATURE to Res.string.sensor_name_temperature,
    SensorType.GRAVITY to Res.string.sensor_name_gravity,
)

@Composable
internal fun fieldName(name: String): String {
    fieldNames[name]?.let { return it.str() }
    val number = name.substringAfterLast(' ', "")
    val base = fieldNames[name.substringBeforeLast(' ')]
    return if (base != null && number.toIntOrNull() != null) "${base.str()} $number" else name
}

@Composable
internal fun fieldValue(value: String): String = fieldValues[value]?.str() ?: value

@Composable
internal fun sensorName(type: SensorType): String = sensorNames.getValue(type).str()
