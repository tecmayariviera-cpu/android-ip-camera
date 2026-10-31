package com.github.digitallyrefined.androidipcamera.activities

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.digitallyrefined.androidipcamera.helpers.RecordingScheduler
import com.github.digitallyrefined.androidipcamera.helpers.ScheduleRule
import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * Lists the recording windows and lets the user add, edit, switch off and delete them.
 * Built in code (no layout XML) to keep the change small. Every save asks the service to
 * re-check the schedule, which starts/stops recording if needed and re-arms the alarm.
 */
class ScheduleActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var rules: MutableList<ScheduleRule> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Horarios de grabación"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad) }
        root.addView(statusView)

        val addButton = Button(this).apply {
            text = "Agregar horario"
            setOnClickListener { showEditor(null) }
        }
        root.addView(addButton)

        root.addView(TextView(this).apply {
            text = "Toca un horario para editarlo. Mantén presionado para borrarlo."
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply {
            adapter = this@ScheduleActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> showEditor(rules[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(rules[pos]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        reload()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun reload() {
        rules = RecordingScheduler.loadRules(this).toMutableList()
        adapter.clear()
        adapter.addAll(rules.map { describe(it) })
        adapter.notifyDataSetChanged()
        statusView.text = summary(this)
    }

    private fun persist() {
        RecordingScheduler.saveRules(this, rules)
        RecordingScheduler.requestCheck(this)
        reload()
    }

    private fun confirmDelete(rule: ScheduleRule) {
        AlertDialog.Builder(this)
            .setTitle("Borrar horario")
            .setMessage(describe(rule))
            .setPositiveButton("Borrar") { _, _ ->
                rules.removeAll { it.id == rule.id }
                persist()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showEditor(existing: ScheduleRule?) {
        var start = existing?.startMinute ?: 22 * 60
        var end = existing?.endMinute ?: 6 * 60
        val pad = dp(16)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val enabledBox = CheckBox(this).apply {
            text = "Activo"
            isChecked = existing?.enabled ?: true
        }
        box.addView(enabledBox)

        val startBtn = Button(this)
        val endBtn = Button(this)
        fun refreshTimes() {
            startBtn.text = "Inicio: ${ScheduleRule.formatMinute(start)}"
            endBtn.text = "Fin: ${ScheduleRule.formatMinute(end)}" +
                if (end <= start) " (día siguiente)" else ""
        }
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> start = h * 60 + m; refreshTimes() },
                start / 60, start % 60, true).show()
        }
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> end = h * 60 + m; refreshTimes() },
                end / 60, end % 60, true).show()
        }
        refreshTimes()
        box.addView(startBtn)
        box.addView(endBtn)

        box.addView(TextView(this).apply { text = "Días (día en que empieza):"; setPadding(0, pad / 2, 0, 0) })
        val dayBoxes = DAYS.map { (calDay, label) ->
            CheckBox(this).apply {
                text = label
                isChecked = existing?.days?.contains(calDay) ?: true
                box.addView(this)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuevo horario" else "Editar horario")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val days = DAYS.filterIndexed { i, _ -> dayBoxes[i].isChecked }.map { it.first }.toSet()
                val rule = ScheduleRule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    enabled = enabledBox.isChecked,
                    days = days,
                    startMinute = start,
                    endMinute = end
                )
                if (!rule.isValid()) {
                    Toast.makeText(this,
                        if (days.isEmpty()) "Elige al menos un día" else "Inicio y fin no pueden ser iguales",
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val idx = rules.indexOfFirst { it.id == rule.id }
                if (idx >= 0) rules[idx] = rule else rules.add(rule)
                persist()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Monday-first display order, mapped to Calendar.DAY_OF_WEEK values. */
        private val DAYS = listOf(
            Calendar.MONDAY to "Lunes",
            Calendar.TUESDAY to "Martes",
            Calendar.WEDNESDAY to "Miércoles",
            Calendar.THURSDAY to "Jueves",
            Calendar.FRIDAY to "Viernes",
            Calendar.SATURDAY to "Sábado",
            Calendar.SUNDAY to "Domingo"
        )
        private val SHORT = mapOf(
            Calendar.MONDAY to "Lun", Calendar.TUESDAY to "Mar", Calendar.WEDNESDAY to "Mié",
            Calendar.THURSDAY to "Jue", Calendar.FRIDAY to "Vie", Calendar.SATURDAY to "Sáb",
            Calendar.SUNDAY to "Dom"
        )

        fun describe(rule: ScheduleRule): String {
            val days = when {
                rule.days.size == 7 -> "Todos los días"
                rule.days == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                    Calendar.THURSDAY, Calendar.FRIDAY) -> "Lunes a viernes"
                else -> DAYS.map { it.first }.filter { it in rule.days }.joinToString(", ") { SHORT[it] ?: "" }
            }
            val overnight = if (rule.endMinute <= rule.startMinute) " (+1 día)" else ""
            val off = if (rule.enabled) "" else "  [apagado]"
            return "${ScheduleRule.formatMinute(rule.startMinute)} – ${ScheduleRule.formatMinute(rule.endMinute)}$overnight · $days$off"
        }

        /** One-line status for this screen and the settings entry. */
        fun summary(context: Context): String {
            val rules = RecordingScheduler.loadRules(context)
            if (rules.isEmpty()) return "Sin horarios. Toca para agregar uno."
            if (!RecordingScheduler.isEnabled(context)) {
                return "${rules.size} horario(s). La grabación programada está apagada en Ajustes."
            }
            val now = System.currentTimeMillis()
            val recording = RecordingScheduler.shouldRecordNow(context, now)
            val next = RecordingScheduler.nextChange(context, now)
                ?: return "${rules.size} horario(s), ninguno activo."
            val fmt = DateFormat.getMediumDateFormat(context).format(Date(next)) + " " +
                DateFormat.getTimeFormat(context).format(Date(next))
            return if (recording) "Grabando ahora. Se detiene: $fmt"
            else "${rules.size} horario(s). Próxima grabación: $fmt"
        }
    }
}
