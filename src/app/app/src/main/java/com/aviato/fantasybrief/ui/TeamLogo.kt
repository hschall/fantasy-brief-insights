package com.aviato.fantasybrief.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.aviato.fantasybrief.data.SecretStore
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * ESPN team logos.
 *
 * Two sources, verified 2026-09-08:
 *   g.espncdn.com        stock and logo-pack SVGs, no auth
 *   mystique-api...      custom uploads, JPEG, 401 WITHOUT the ESPN cookies
 *
 * So the loader needs an SVG decoder and a cookie header scoped to the second
 * host. Five of twenty teams across both leagues use custom uploads, and any
 * of them can fail — offline, expired cookies, a team with no logo — so the
 * initial-letter fallback is the common case, not an edge case.
 */
private var loaderRef: ImageLoader? = null

private fun loader(context: Context): ImageLoader =
    loaderRef ?: ImageLoader.Builder(context)
        .components { add(SvgDecoder.Factory()) }
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    // Cookies ONLY to ESPN's own upload host. Sending them
                    // anywhere else would leak account credentials.
                    if (req.url.host.contains("fantasy.espn.com")) {
                        val secrets = SecretStore(context)
                        if (secrets.hasCredentials) {
                            return@addInterceptor chain.proceed(
                                req.newBuilder()
                                    .header("Cookie", secrets.cookieHeader())
                                    .build()
                            )
                        }
                    }
                    chain.proceed(req)
                }
                .build()
        }
        .build()
        .also { loaderRef = it }

@Composable
fun TeamLogo(
    url: String?,
    name: String,
    size: Dp = 26.dp,
    tint: Color = Fb.Teal
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(size / 3.2f)

    Box(
        Modifier.size(size).clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            Initial(name, size, tint, shape)
            return@Box
        }
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
            imageLoader = loader(context),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(shape),
            // Both states show the fallback, so a slow custom upload never
            // leaves a hole in the layout.
            loading = { Initial(name, size, tint, shape) },
            error = { Initial(name, size, tint, shape) }
        )
    }
}

@Composable
private fun Initial(
    name: String,
    size: Dp,
    tint: Color,
    shape: RoundedCornerShape
) {
    Box(
        Modifier.size(size).clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" },
            color = tint,
            fontSize = (size.value * 0.45f).sp
        )
    }
}


/**
 * NFL team logo, from ESPN's public CDN. No auth.
 *
 * Verified 2026-09-08: a.espncdn.com/i/teamlogos/nfl/500/{abbrev}.png returns
 * a 38 KB PNG. There is a 500-dark variant at the same size; the plain one
 * reads better on this ground.
 */
@Composable
fun ProTeamLogo(
    abbrev: String,
    size: Dp = 26.dp,
    tint: Color = Fb.Teal
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(size / 4f)
    val code = abbrev.lowercase().trim()

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (code.isBlank() || code == "fa") {
            Initial(abbrev, size, tint, shape)
            return@Box
        }
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data("https://a.espncdn.com/i/teamlogos/nfl/500/$code.png")
                .crossfade(true).build(),
            imageLoader = loader(context),
            contentDescription = abbrev,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
            // The abbreviation is a perfectly good fallback and it is what the
            // app showed before logos existed.
            loading = { Initial(abbrev, size, tint, shape) },
            error = { Initial(abbrev, size, tint, shape) }
        )
    }
}

/**
 * Player headshot. The fantasy playerId IS the site athlete id, so no lookup.
 *
 * 258 KB each, which is why this belongs on a detail screen rather than in a
 * list of twelve rows. D/ST ids are negative and have no headshot.
 */
@Composable
fun PlayerHeadshot(
    playerId: Int,
    name: String,
    size: Dp = 64.dp,
    tint: Color = Fb.Teal
) {
    val context = LocalContext.current
    val shape = androidx.compose.foundation.shape.CircleShape

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (playerId <= 0) {
            Initial(name, size, tint, RoundedCornerShape(size / 3.2f))
            return@Box
        }
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data("https://a.espncdn.com/i/headshots/nfl/players/full/$playerId.png")
                .crossfade(true).build(),
            imageLoader = loader(context),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape)
                .background(tint.copy(alpha = 0.10f)),
            loading = { Initial(name, size, tint, RoundedCornerShape(size / 3.2f)) },
            error = { Initial(name, size, tint, RoundedCornerShape(size / 3.2f)) }
        )
    }
}


/**
 * Headshot with the NFL team logo badged on it.
 *
 * Replaces a bare team mark in list rows: the face identifies the player and
 * the badge still answers "who does he play for". 258 KB per headshot, so a
 * full Matchup screen is ~8 MB on a cold cache — Coil caches to disk and rows
 * show the fallback until each lands, so it degrades rather than blocking.
 */
@Composable
fun PlayerFace(
    playerId: Int,
    name: String,
    proAbbrev: String,
    size: Dp = 34.dp,
    tint: Color = Fb.Teal
) {
    Box(Modifier.size(size), contentAlignment = Alignment.BottomEnd) {
        PlayerHeadshot(playerId, name, size, tint)
        Box(
            Modifier.size(size * 0.46f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Fb.Ground),
            contentAlignment = Alignment.Center
        ) {
            ProTeamLogo(proAbbrev, size * 0.42f, tint)
        }
    }
}


/**
 * Headshot with the analyst consensus rank badged on it.
 *
 * The rank is the only outside opinion in the app — eight sources, weekly.
 * On the meta line it reads as one more abbreviation; on the image it is the
 * first thing you see.
 */
@Composable
fun RankedHeadshot(
    playerId: Int,
    name: String,
    rankLabel: String?,
    /** Places moved since the last run. NEGATIVE is an improvement. */
    rankDelta: Int? = null,
    size: Dp = 40.dp,
    tint: Color = Fb.Teal,
    isDst: Boolean = false,
    proAbbrev: String = ""
) {
    // The badge overhangs the top-left corner, so the frame is padded to
    // make room rather than the badge clipping against the image.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (isDst) ProTeamLogo(proAbbrev, size, tint)
        else PlayerHeadshot(playerId, name, size, tint)

        if (rankDelta != null && rankDelta != 0 && rankLabel != null) {
            Text(
                if (rankDelta < 0) "\u25B2" else "\u25BC",
                color = if (rankDelta < 0) Color(0xFF4FD0B0) else Color(0xFFE78E88),
                fontSize = (size.value * 0.20f).sp,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
            )
        }

        rankLabel?.let {
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset(x = (-3).dp, y = (-3).dp)
                    .size(size * 0.40f)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFF2F6FA)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    it,
                    color = when {
                        rankDelta == null || rankDelta == 0 -> Color(0xFF0B1320)
                        // Lower rank is better, so a negative delta is good.
                        rankDelta < 0 -> Color(0xFF0A7A3C)
                        else -> Color(0xFF9B2222)
                    },
                    fontSize = (size.value * 0.22f).sp,
                    lineHeight = (size.value * 0.22f).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                )
            }
        }
    }
}
