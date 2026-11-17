package com.syncedwallpapers.app.ui.screens.propose

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.syncedwallpapers.app.core.rules.ScheduleRules
import com.syncedwallpapers.app.core.rules.TimeWindow
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.Commitment
import com.syncedwallpapers.app.data.remote.GroupDto
import com.syncedwallpapers.app.data.remote.Slot
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.NewProposal
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.Target
import com.syncedwallpapers.app.delivery.ImageProcessor
import com.syncedwallpapers.app.ui.components.DayBar
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Legend
import com.syncedwallpapers.app.ui.components.PhoneFrame
import com.syncedwallpapers.app.ui.components.PrimaryButton
import com.syncedwallpapers.app.ui.components.SecureScreen
import com.syncedwallpapers.app.ui.components.SlotKind
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.components.TimelineSlot
import com.syncedwallpapers.app.ui.nav.ProposeRoute
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.inject.Inject

enum class ProposeStep(val title: String) { PHOTO("Pick a photo"), FOCAL("Focal point"), TARGET("Where"), TIME("When"), TEXT("Reminder text"), REVIEW("Review") }

data class ProposeState(
    val step: ProposeStep = ProposeStep.PHOTO,
    val group: GroupDto? = null,
    val jpeg: ByteArray? = null,
    val preview: Bitmap? = null,
    val focalX: Float = 0.5f,
    val focalY: Float = 0.5f,
    val target: Target = Target.BOTH,
    val startDate: LocalDate? = null,
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate? = null,
    val endTime: LocalTime = LocalTime.of(21, 0),
    val reminder: String = "",
    val style: Int = 0,
    val slots: List<Slot> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val replaces: String? = null,
    val replacesImageUrl: String? = null,
    val sent: Boolean = false,
) {
    val zone: ZoneId get() = group?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    fun startInstant(): Instant? = startDate?.let { ZonedDateTime.of(it, startTime, zone).toInstant() }
    fun endInstant(): Instant? = (endDate ?: startDate)?.let { ZonedDateTime.of(it, endTime, zone).toInstant() }
    fun window(): TimeWindow? { val s = startInstant(); val e = endInstant(); return if (s != null && e != null && e.isAfter(s)) TimeWindow(s, e) else null }
}

@HiltViewModel
class ProposeViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val groups: GroupRepository,
    private val proposals: ProposalRepository,
    private val images: ImageProcessor,
    val clock: ServerClock,
) : ViewModel() {
    private val route = handle.toRoute<ProposeRoute>()
    private val _state = MutableStateFlow(ProposeState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val err = runAction {
                val group = groups.detail(route.groupId).group
                val now = clock.now()
                val slots = proposals.takenSlots(route.groupId, now, now.plus(Duration.ofDays(62)))
                val commitments = proposals.myCommitments()
                _state.update { it.copy(group = group, slots = slots.filter { s -> s.proposalId != route.replaces }, commitments = commitments) }
                val replaces = route.replaces
                if (replaces != null) {
                    // One-tap re-propose: same photo and settings, new time, votes reset.
                    val old = proposals.get(replaces)
                    val zone = ZoneId.of(group.timeZone)
                    val s = route.start?.let { Instant.ofEpochMilli(it) } ?: old.startAt
                    val e = route.end?.let { Instant.ofEpochMilli(it) } ?: old.endAt
                    _state.update { st -> st.copy(step = ProposeStep.TIME, replaces = replaces, focalX = old.focalX, focalY = old.focalY,
                        target = Target.valueOf(old.target), reminder = old.reminderText ?: "", style = old.reminderStyle,
                        replacesImageUrl = runCatching { proposals.signedUrl(old.imagePath) }.getOrNull(),
                        startDate = s.atZone(zone).toLocalDate(), startTime = s.atZone(zone).toLocalTime().withSecond(0).withNano(0),
                        endDate = e.atZone(zone).toLocalDate(), endTime = e.atZone(zone).toLocalTime().withSecond(0).withNano(0),
                        commitments = st.commitments.filter { c -> c.proposalId != replaces }) }
                }
            }
            if (err != null) _state.update { it.copy(error = err) }
        }
    }

    fun pick(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val err = runAction {
            val jpeg = withContext(Dispatchers.Default) { images.prepareUpload(uri) }
            val bmp = withContext(Dispatchers.Default) { images.decodeBytes(jpeg) }
            _state.update { it.copy(jpeg = jpeg, preview = bmp, step = ProposeStep.FOCAL) }
        }
        _state.update { it.copy(busy = false, error = err) }
    }

    fun setFocal(x: Float, y: Float) = _state.update { it.copy(focalX = x.coerceIn(0f, 1f), focalY = y.coerceIn(0f, 1f)) }
    fun setTarget(t: Target) = _state.update { it.copy(target = t) }
    fun setStartDate(d: LocalDate) = _state.update { it.copy(startDate = d, endDate = if (it.endDate == null || it.endDate < d) d else it.endDate) }
    fun setStartTime(t: LocalTime) = _state.update { it.copy(startTime = t) }
    fun setEndDate(d: LocalDate) = _state.update { it.copy(endDate = d) }
    fun setEndTime(t: LocalTime) = _state.update { it.copy(endTime = t) }
    fun setReminder(s: String) = _state.update { it.copy(reminder = s.take(ScheduleRules.MAX_REMINDER)) }
    fun setStyle(i: Int) = _state.update { it.copy(style = i) }
    fun dismiss() = _state.update { it.copy(error = null) }

    /** Problems with the chosen time: rules, taken group slots, personal commitments. */
    fun timeProblem(s: ProposeState): String? {
        val start = s.startInstant() ?: return "Pick a start date."
        val end = s.endInstant() ?: return "Pick an end time."
        ScheduleRules.validate(start, end, clock.now())?.let { return ScheduleRules.message(it) }
        val w = TimeWindow(start, end)
        if (s.slots.any { TimeWindow(it.startAt, it.endAt).overlaps(w) }) return "That overlaps a slot that's already taken in this group (shown in grey)."
        s.commitments.firstOrNull { TimeWindow(it.startAt, it.endAt).overlaps(w) }?.let { c ->
            return "Overlaps with “${c.groupName}” ${TimeFormat.window(c.startAt, c.endAt, ZoneId.systemDefault())} on your phone."
        }
        return null
    }

    fun next() = _state.update {
        val order = ProposeStep.entries
        it.copy(step = order[(it.step.ordinal + 1).coerceAtMost(order.lastIndex)], error = null)
    }
    fun back(): Boolean {
        val s = _state.value
        val first = if (s.replaces != null) ProposeStep.TIME else ProposeStep.PHOTO
        if (s.step == first) return false
        _state.update { it.copy(step = ProposeStep.entries[it.step.ordinal - 1]) }
        return true
    }

    fun send() = viewModelScope.launch {
        val s = _state.value
        val w = s.window() ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        val err = runAction {
            proposals.create(NewProposal(route.groupId, w.start, w.end, s.target, s.focalX, s.focalY,
                s.reminder.ifBlank { null }, s.style, s.jpeg, s.replaces))
        }
        _state.update { it.copy(busy = false, error = err, sent = err == null) }
    }

    override fun onCleared() { _state.value.preview?.recycle() }
}

@Composable
fun ProposeScreen(onClose: () -> Unit, vm: ProposeViewModel = hiltViewModel()) {
    SecureScreen()
    val s by vm.state.collectAsState()
    if (s.sent) { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() } }
    BackHandler { if (!vm.back()) onClose() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.pick(uri) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (s.replaces != null) "Re-propose · ${s.step.title}" else s.step.title) },
            navigationIcon = { IconButton(onClick = { if (!vm.back()) onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            s.error?.let { ErrorBanner(it, onDismiss = vm::dismiss) }
            val frame: @Composable (Boolean) -> Unit = { reminder ->
                PhoneFrame(Modifier.align(Alignment.CenterHorizontally), bitmap = s.preview, url = if (s.preview == null) s.replacesImageUrl else null,
                    cacheKey = s.replaces, focalX = s.focalX, focalY = s.focalY,
                    reminder = if (reminder) s.reminder else null, reminderStyle = s.style)
            }
            when (s.step) {
                ProposeStep.PHOTO -> {
                    Text("JPEG, PNG, WebP or HEIC, up to 20 MB. We upload a smaller copy with location and other metadata removed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton("Choose photo", busy = s.busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.align(Alignment.CenterHorizontally).size(64.dp), tint = MaterialTheme.colorScheme.outline)
                }
                ProposeStep.FOCAL -> {
                    Text("Tap the part of the photo that must stay visible. Each phone crops around it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.preview?.let { FocalPicker(it, s.focalX, s.focalY, vm::setFocal) }
                    Text("Preview on your screen (the dashed box is where the clock sits):", style = MaterialTheme.typography.bodySmall)
                    frame(false)
                    PrimaryButton("Next", onClick = vm::next)
                }
                ProposeStep.TARGET -> {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Target.entries.forEachIndexed { i, t ->
                            SegmentedButton(s.target == t, { vm.setTarget(t) }, SegmentedButtonDefaults.itemShape(i, Target.entries.size)) { Text(t.label) }
                        }
                    }
                    frame(false)
                    PrimaryButton("Next", onClick = vm::next)
                }
                ProposeStep.TIME -> TimeStep(s, vm)
                ProposeStep.TEXT -> {
                    OutlinedTextField(s.reminder, vm::setReminder, label = { Text("Reminder text (optional)") },
                        supportingText = { Text("${s.reminder.length}/${ScheduleRules.MAX_REMINDER} · placed away from the clock") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Soft", "Bold", "Minimal").forEachIndexed { i, label -> FilterChip(s.style == i, { vm.setStyle(i) }, label = { Text(label) }) }
                    }
                    frame(true)
                    PrimaryButton("Next", onClick = vm::next)
                }
                ProposeStep.REVIEW -> {
                    frame(true)
                    val w = s.window()
                    SwCard {
                        Text(s.group?.name ?: "", style = MaterialTheme.typography.titleMedium)
                        if (w != null) {
                            Text(TimeFormat.window(w.start, w.end, s.zone))
                            TimeFormat.localWindowIfDifferent(w.start, w.end, s.zone)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("Voting closes ${TimeFormat.groupAndLocal(ScheduleRules.voteDeadline(w.start), s.zone)} (15 minutes before the start).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(s.target.label, style = MaterialTheme.typography.bodySmall)
                    }
                    InfoBanner("Every member is notified and must approve before the deadline. One “no” stops it. You can't edit it after sending, but you can cancel it.")
                    PrimaryButton("Send proposal", onClick = vm::send, busy = s.busy, enabled = w != null && vm.timeProblem(s) == null)
                }
            }
        }
    }
}

@Composable
private fun FocalPicker(bitmap: Bitmap, fx: Float, fy: Float, onSet: (Float, Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clip(RoundedCornerShape(16.dp))
        .pointerInput(Unit) { detectTapGestures { o -> if (size.width > 0) onSet(o.x / size.width, o.y / size.height) } }
        .onSizeChanged { size = it }) {
        Image(bitmap.asImageBitmap(), "Photo", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp).offset(x = maxWidth * fx - 14.dp, y = maxHeight * fy - 14.dp)) {}
        }
    }
}


@Composable
private fun TimeStep(s: ProposeState, vm: ProposeViewModel) {
    var picking by remember { mutableStateOf<String?>(null) }
    val zone = s.zone
    Text("Times are in the group's zone, ${s.group?.timeZone ?: ""}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = "startDate" }, modifier = Modifier.weight(1f)) { Text(s.startDate?.toString() ?: "Start date") }
        OutlinedButton(onClick = { picking = "startTime" }, modifier = Modifier.weight(1f)) { Text("From ${s.startTime}") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = "endDate" }, modifier = Modifier.weight(1f)) { Text((s.endDate ?: s.startDate)?.toString() ?: "End date") }
        OutlinedButton(onClick = { picking = "endTime" }, modifier = Modifier.weight(1f)) { Text("Until ${s.endTime}") }
    }
    s.startDate?.let { day ->
        // Taken group slots and the user's other commitments are shaded; the choice is highlighted.
        val taken = s.slots.map { TimelineSlot(it.startAt, it.endAt, SlotKind.CONFLICT) }
        val mine = s.commitments.map { TimelineSlot(it.startAt, it.endAt, SlotKind.CONFLICT) }
        val chosen = s.window()?.let { listOf(TimelineSlot(it.start, it.end, SlotKind.MINE)) } ?: emptyList()
        SwCard {
            val days = generateSequence(day) { it.plusDays(1) }.takeWhile { it <= (s.endDate ?: day) }.take(7).toList()
            days.forEach { d ->
                Text(d.toString(), style = MaterialTheme.typography.labelSmall)
                DayBar(d, zone, taken + mine + chosen, height = 22)
            }
            Legend(listOf("Taken or your other commitments" to MaterialTheme.colorScheme.error.copy(alpha = 0.35f), "Your choice" to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)))
        }
    }
    s.window()?.let { w ->
        Text(TimeFormat.window(w.start, w.end, zone))
        TimeFormat.localWindowIfDifferent(w.start, w.end, zone)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    val problem = vm.timeProblem(s)
    if (problem != null && s.startDate != null) ErrorBanner(problem)
    PrimaryButton("Next", onClick = { if (s.replaces != null) { vm.next(); vm.next() } else vm.next() }, enabled = problem == null)

    val today = LocalDate.now(zone)
    when (picking) {
        "startDate", "endDate" -> {
            val initial = (if (picking == "startDate") s.startDate else s.endDate ?: s.startDate) ?: today
            val st = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        return !d.isBefore(today) && !d.isAfter(today.plusDays(60))
                    }
                })
            DatePickerDialog(onDismissRequest = { picking = null },
                confirmButton = { TextButton(onClick = {
                    st.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (picking == "startDate") vm.setStartDate(d) else vm.setEndDate(d)
                    }
                    picking = null
                }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } }) { DatePicker(st) }
        }
        "startTime", "endTime" -> {
            val t = if (picking == "startTime") s.startTime else s.endTime
            val st = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
            androidx.compose.material3.AlertDialog(onDismissRequest = { picking = null },
                confirmButton = { Button(onClick = {
                    val v = LocalTime.of(st.hour, st.minute)
                    if (picking == "startTime") vm.setStartTime(v) else vm.setEndTime(v)
                    picking = null
                }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
                text = { TimePicker(st) })
        }
    }
}
