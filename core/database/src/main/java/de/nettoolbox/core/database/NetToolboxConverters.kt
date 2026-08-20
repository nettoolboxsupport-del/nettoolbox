package de.nettoolbox.core.database

import androidx.room.TypeConverter
import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.KnownCellSource
import de.nettoolbox.core.database.entity.ToolType

/**
 * Enums are stored by name, not by ordinal.
 *
 * An ordinal would silently re-map every stored row the moment a new value is
 * inserted in the middle of an enum - the kind of corruption that only shows up
 * months later in an exported drive test.
 */
object NetToolboxConverters {

    @TypeConverter
    fun ratToString(value: RadioAccessTechnology): String = value.name

    @TypeConverter
    fun stringToRat(value: String): RadioAccessTechnology =
        RadioAccessTechnology.fromNameOrUnknown(value)

    @TypeConverter
    fun sourceToString(value: KnownCellSource): String = value.name

    @TypeConverter
    fun stringToSource(value: String): KnownCellSource =
        KnownCellSource.entries.firstOrNull { it.name == value } ?: KnownCellSource.LOCAL_IMPORT

    @TypeConverter
    fun toolTypeToString(value: ToolType): String = value.name

    @TypeConverter
    fun stringToToolType(value: String): ToolType? =
        ToolType.entries.firstOrNull { it.name == value }
}
