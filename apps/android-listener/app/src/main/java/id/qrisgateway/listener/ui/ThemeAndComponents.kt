package id.qrisgateway.listener.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.qrisgateway.listener.InvoiceItem
import java.text.NumberFormat

val StzenDark = Color(0xFF141A12)
val StzenEmerald = Color(0xFF1F8A58)
val StzenEmeraldLight = Color(0xFFEAF4DF)
val StzenLime = Color(0xFFCCFF00)
val StzenSurface = Color(0xFFF4F9EE)
val StzenCardBorder = Color(0xFFDCE8D5)
val StzenMuted = Color(0xFF52604C)
val StzenSubtle = Color(0xFF8A9985)
val StzenInputBg = Color(0xFFF8FAF7)
val StzenPendingBg = Color(0xFFFEF3C7)
val StzenPendingFg = Color(0xFFD97706)
val StzenErrorBg = Color(0xFFFFE4E6)
val StzenErrorFg = Color(0xFFE11D48)

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = StzenMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun RoundedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    Surface(
        modifier = cardModifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, StzenCardBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun RoundedInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingUnit: String? = null,
    singleLine: Boolean = true
) {
    var revealed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = StzenDark,
            modifier = Modifier.padding(bottom = 5.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, fontSize = 12.sp, color = StzenSubtle)
            },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            visualTransformation = if (isSecret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = {
                when {
                    isSecret -> {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = StzenMuted
                            )
                        }
                    }
                    trailingUnit != null -> {
                        Text(
                            text = trailingUnit,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StzenMuted,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StzenInputBg,
                unfocusedContainerColor = StzenInputBg,
                focusedBorderColor = StzenEmerald,
                unfocusedBorderColor = StzenCardBorder,
                focusedTextColor = StzenDark,
                unfocusedTextColor = StzenDark
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StatusPill(status: String) {
    val (label, bg, fg, icon) = when (status.uppercase()) {
        "PAID" -> Quadruple("Berhasil", StzenEmeraldLight, StzenEmerald, Icons.Rounded.CheckCircle)
        "PENDING" -> Quadruple("Menunggu", StzenPendingBg, StzenPendingFg, Icons.Rounded.Schedule)
        "CANCELLED" -> Quadruple("Dibatalkan", StzenErrorBg, StzenErrorFg, Icons.Rounded.Cancel)
        else -> Quadruple("Kedaluwarsa", StzenErrorBg, StzenErrorFg, Icons.Rounded.Schedule)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@Composable
fun TransactionRowCard(
    item: InvoiceItem,
    idrFormat: NumberFormat,
    onClick: () -> Unit
) {
    val isPaid = item.status.equals("PAID", ignoreCase = true)
    val isPending = item.status.equals("PENDING", ignoreCase = true)
    val circleBg = when {
        isPaid -> StzenEmeraldLight
        isPending -> StzenPendingBg
        else -> StzenErrorBg
    }
    val circleFg = when {
        isPaid -> StzenEmerald
        isPending -> StzenPendingFg
        else -> StzenErrorFg
    }
    val icon = when {
        isPaid -> Icons.Rounded.CheckCircle
        isPending -> Icons.Rounded.Schedule
        else -> Icons.Rounded.Cancel
    }

    RoundedCard(
        modifier = Modifier.padding(bottom = 10.dp),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(circleBg)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = circleFg,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.externalOrderId.ifBlank { item.id },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = StzenDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.createdAt.take(16).replace("T", " ")} • CB: ${item.callbackStatus}",
                    fontSize = 11.sp,
                    color = StzenSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (isPaid) "+Rp " else "Rp ") + idrFormat.format(item.totalAmount),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    color = if (isPaid) StzenEmerald else StzenDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                StatusPill(item.status)
            }
        }
    }
}

@Composable
fun StzenBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onQuickQr: () -> Unit
) {
    Surface(
        color = Color.White,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, StzenCardBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(66.dp)
                .padding(horizontal = 6.dp)
        ) {
            BottomNavItem("Beranda", Icons.Rounded.Home, selectedTab == 0) { onSelectTab(0) }
            BottomNavItem("Riwayat", Icons.Rounded.History, selectedTab == 1) { onSelectTab(1) }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(y = (-8).dp)
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(StzenLime)
                    .border(2.dp, StzenDark, CircleShape)
                    .clickable { onQuickQr() }
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = "Buat QR Cepat",
                    tint = StzenDark,
                    modifier = Modifier.size(28.dp)
                )
            }

            BottomNavItem("Setting", Icons.Rounded.Settings, selectedTab == 2) { onSelectTab(2) }
            BottomNavItem("Profil", Icons.Rounded.Person, selectedTab == 3) { onSelectTab(3) }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) StzenEmerald else StzenSubtle
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint
        )
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
