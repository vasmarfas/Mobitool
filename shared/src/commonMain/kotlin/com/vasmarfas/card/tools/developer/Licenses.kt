package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Commercial.ANY
import com.vasmarfas.card.tools.developer.Commercial.INTERNAL
import com.vasmarfas.card.tools.developer.Commercial.NONE
import com.vasmarfas.card.tools.developer.Commercial.NON_COMPETING
import com.vasmarfas.card.tools.developer.Commercial.NON_PRODUCTION
import com.vasmarfas.card.tools.developer.Commercial.NO_HOSTING
import com.vasmarfas.card.tools.developer.Commercial.SMALL_BUSINESS
import com.vasmarfas.card.tools.developer.Commercial.TRIAL
import com.vasmarfas.card.tools.developer.LicenseFamily.CREATIVE_COMMONS
import com.vasmarfas.card.tools.developer.LicenseFamily.FONTS_DATA
import com.vasmarfas.card.tools.developer.LicenseFamily.PERMISSIVE
import com.vasmarfas.card.tools.developer.LicenseFamily.PROPRIETARY
import com.vasmarfas.card.tools.developer.LicenseFamily.PUBLIC_DOMAIN
import com.vasmarfas.card.tools.developer.LicenseFamily.SOURCE_AVAILABLE
import com.vasmarfas.card.tools.developer.LicenseFamily.STRONG_COPYLEFT
import com.vasmarfas.card.tools.developer.LicenseFamily.WEAK_COPYLEFT
import com.vasmarfas.card.tools.developer.LicenseFlag.DELAYED
import com.vasmarfas.card.tools.developer.LicenseFlag.DISCLOSE
import com.vasmarfas.card.tools.developer.LicenseFlag.DISTRIBUTE
import com.vasmarfas.card.tools.developer.LicenseFlag.FSF
import com.vasmarfas.card.tools.developer.LicenseFlag.GPL_COMPATIBLE
import com.vasmarfas.card.tools.developer.LicenseFlag.MODIFY
import com.vasmarfas.card.tools.developer.LicenseFlag.NOTICE
import com.vasmarfas.card.tools.developer.LicenseFlag.OSI
import com.vasmarfas.card.tools.developer.LicenseFlag.PATENT
import com.vasmarfas.card.tools.developer.LicenseFlag.STATE_CHANGES
import com.vasmarfas.card.tools.developer.LicenseSubject.CODE
import com.vasmarfas.card.tools.developer.LicenseSubject.CONTENT
import com.vasmarfas.card.tools.developer.LicenseSubject.DATA
import com.vasmarfas.card.tools.developer.LicenseSubject.FONT
import com.vasmarfas.card.tools.developer.LicenseSubject.HARDWARE
import kotlin.math.abs
import org.jetbrains.compose.resources.StringResource

enum class LicenseFamily(val title: StringResource) {
    PERMISSIVE(Res.string.license_family_permissive),
    PUBLIC_DOMAIN(Res.string.license_family_public_domain),
    WEAK_COPYLEFT(Res.string.license_family_weak),
    STRONG_COPYLEFT(Res.string.license_family_strong),
    SOURCE_AVAILABLE(Res.string.license_family_source_available),
    CREATIVE_COMMONS(Res.string.license_family_cc),
    HARDWARE(Res.string.license_family_hardware),
    FONTS_DATA(Res.string.license_family_fonts_data),
    PROPRIETARY(Res.string.license_family_proprietary),
}

enum class LicenseSubject(val title: StringResource) {
    CODE(Res.string.license_subject_code),
    CONTENT(Res.string.license_subject_content),
    HARDWARE(Res.string.license_subject_hardware),
    FONT(Res.string.license_subject_font),
    DATA(Res.string.license_subject_data),
}

enum class Commercial(val cell: StringResource) {
    ANY(Res.string.license_yes),
    NON_COMPETING(Res.string.license_commercial_non_competing),
    NO_HOSTING(Res.string.license_commercial_no_hosting),
    NON_PRODUCTION(Res.string.license_commercial_non_production),
    SMALL_BUSINESS(Res.string.license_commercial_small),
    INTERNAL(Res.string.license_commercial_internal),
    TRIAL(Res.string.license_commercial_trial),
    NONE(Res.string.license_no),
}

enum class Copyleft(val level: Int, val cell: StringResource) {
    NONE(0, Res.string.license_no),
    FILE(1, Res.string.license_copyleft_file),
    LIBRARY(1, Res.string.license_copyleft_library),
    PROJECT(2, Res.string.license_copyleft_project),
    NETWORK(3, Res.string.license_copyleft_network),
}

enum class LicenseFlag { MODIFY, DISTRIBUTE, NOTICE, STATE_CHANGES, DISCLOSE, PATENT, DELAYED, OSI, FSF, GPL_COMPATIBLE }

class License(
    val id: String,
    val short: String,
    val name: String,
    val family: LicenseFamily,
    val subjects: Set<LicenseSubject>,
    val summary: StringResource,
    val commercial: Commercial,
    val copyleft: Copyleft,
    val flags: Set<LicenseFlag>,
    val popularity: Int,
    val words: Int,
    val file: String,
    val url: String,
    val usedBy: String = "",
    val notes: List<StringResource> = emptyList(),
) {
    operator fun contains(flag: LicenseFlag): Boolean = flag in flags

    val versioned: Boolean get() = id.startsWith("GPL-") || id.startsWith("LGPL-") || id.startsWith("AGPL-") || id.startsWith("GFDL-")

    fun spdx(orLater: Boolean): String = if (versioned) id + if (orLater) "-or-later" else "-only" else id

    val inSpdxList: Boolean get() = !id.startsWith("LicenseRef-")

    val markdown: Boolean get() = file.endsWith(".md")
}

enum class LicenseFeature(val title: StringResource) {
    COMMERCIAL(Res.string.license_feature_commercial),
    MODIFY(Res.string.license_feature_modify),
    DISTRIBUTE(Res.string.license_feature_distribute),
    PATENT(Res.string.license_feature_patent),
    NOTICE(Res.string.license_feature_notice),
    STATE_CHANGES(Res.string.license_feature_state_changes),
    DISCLOSE(Res.string.license_feature_disclose),
    SAME_LICENSE(Res.string.license_feature_same_license),
    OSI(Res.string.license_feature_osi),
    FSF(Res.string.license_feature_fsf),
    GPL(Res.string.license_feature_gpl),
}

enum class Mark { YES, PARTLY, NO }

class FeatureValue(val mark: Mark, val text: StringResource)

private val OPEN = setOf(MODIFY, DISTRIBUTE)

private val PERMISSIVE_CODE = OPEN + setOf(NOTICE, OSI, FSF, GPL_COMPATIBLE)

object Licenses {
    val all: List<License> = listOf(
        License(
            "MIT", "MIT", "MIT License", PERMISSIVE, setOf(CODE), Res.string.license_sum_mit, ANY, Copyleft.NONE,
            PERMISSIVE_CODE, 5, 169, "MIT.txt", "https://opensource.org/license/mit", "React, Node.js, Ruby on Rails, jQuery",
        ),
        License(
            "Apache-2.0", "Apache-2.0", "Apache License 2.0", PERMISSIVE, setOf(CODE), Res.string.license_sum_apache, ANY, Copyleft.NONE,
            PERMISSIVE_CODE + setOf(STATE_CHANGES, PATENT), 5, 1581, "Apache-2.0.txt", "https://www.apache.org/licenses/LICENSE-2.0",
            "Android, Kubernetes, Kotlin, Swift", listOf(Res.string.license_note_apache_gpl2),
        ),
        License(
            "BSD-3-Clause", "BSD-3-Clause", "BSD 3-Clause \"New\" or \"Revised\" License", PERMISSIVE, setOf(CODE), Res.string.license_sum_bsd3,
            ANY, Copyleft.NONE, PERMISSIVE_CODE, 4, 220, "BSD-3-Clause.txt", "https://opensource.org/license/bsd-3-clause", "Go, Django, Flutter, NumPy",
        ),
        License(
            "BSD-2-Clause", "BSD-2-Clause", "BSD 2-Clause \"Simplified\" License", PERMISSIVE, setOf(CODE), Res.string.license_sum_bsd2,
            ANY, Copyleft.NONE, PERMISSIVE_CODE, 3, 189, "BSD-2-Clause.txt", "https://opensource.org/license/bsd-2-clause", "FreeBSD, Homebrew",
        ),
        License(
            "ISC", "ISC", "ISC License", PERMISSIVE, setOf(CODE), Res.string.license_sum_isc, ANY, Copyleft.NONE,
            PERMISSIVE_CODE, 3, 116, "ISC.txt", "https://opensource.org/license/isc-license-txt", "OpenBSD, BIND, ISC DHCP",
        ),
        License(
            "Zlib", "Zlib", "zlib License", PERMISSIVE, setOf(CODE), Res.string.license_sum_zlib, ANY, Copyleft.NONE,
            PERMISSIVE_CODE + STATE_CHANGES, 2, 134, "Zlib.txt", "https://opensource.org/license/zlib", "zlib, SDL, Box2D",
        ),
        License(
            "BSL-1.0", "BSL-1.0", "Boost Software License 1.0", PERMISSIVE, setOf(CODE), Res.string.license_sum_boost, ANY, Copyleft.NONE,
            PERMISSIVE_CODE, 2, 212, "BSL-1.0.txt", "https://www.boost.org/LICENSE_1_0.txt", "Boost, Catch2",
        ),
        License(
            "Unlicense", "Unlicense", "The Unlicense", PUBLIC_DOMAIN, setOf(CODE), Res.string.license_sum_unlicense, ANY, Copyleft.NONE,
            OPEN + setOf(OSI, FSF, GPL_COMPATIBLE), 3, 197, "Unlicense.txt", "https://unlicense.org", "yt-dlp, youtube-dl",
            listOf(Res.string.license_note_waiver),
        ),
        License(
            "0BSD", "0BSD", "BSD Zero Clause License", PUBLIC_DOMAIN, setOf(CODE), Res.string.license_sum_0bsd, ANY, Copyleft.NONE,
            OPEN + setOf(OSI, GPL_COMPATIBLE), 2, 103, "0BSD.txt", "https://opensource.org/license/0bsd", "Toybox, tslib",
        ),
        License(
            "MIT-0", "MIT-0", "MIT No Attribution", PUBLIC_DOMAIN, setOf(CODE), Res.string.license_sum_mit0, ANY, Copyleft.NONE,
            OPEN + setOf(OSI, GPL_COMPATIBLE), 2, 144, "MIT-0.txt", "https://opensource.org/license/mit-0", "AWS samples",
        ),
        License(
            "CC0-1.0", "CC0 1.0", "Creative Commons Zero 1.0 Universal", PUBLIC_DOMAIN, setOf(CONTENT, DATA), Res.string.license_sum_cc0, ANY,
            Copyleft.NONE, OPEN + setOf(FSF, GPL_COMPATIBLE), 4, 1066, "CC0-1.0.txt", "https://creativecommons.org/publicdomain/zero/1.0/",
            "Wikidata, Europeana, Smithsonian Open Access", listOf(Res.string.license_note_cc0_patents),
        ),
        License(
            "MPL-2.0", "MPL-2.0", "Mozilla Public License 2.0", WEAK_COPYLEFT, setOf(CODE), Res.string.license_sum_mpl, ANY, Copyleft.FILE,
            OPEN + setOf(NOTICE, DISCLOSE, PATENT, OSI, FSF, GPL_COMPATIBLE), 3, 2435, "MPL-2.0.txt", "https://www.mozilla.org/MPL/2.0/",
            "Firefox, LibreOffice, Syncthing",
        ),
        License(
            "LGPL-2.1", "LGPL-2.1", "GNU Lesser General Public License v2.1", WEAK_COPYLEFT, setOf(CODE), Res.string.license_sum_lgpl21, ANY,
            Copyleft.LIBRARY, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, OSI, FSF, GPL_COMPATIBLE), 3, 4371, "LGPL-2.1.txt",
            "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html", "glibc, FFmpeg, GTK", listOf(Res.string.license_note_lgpl_static),
        ),
        License(
            "LGPL-3.0", "LGPL-3.0", "GNU Lesser General Public License v3.0", WEAK_COPYLEFT, setOf(CODE), Res.string.license_sum_lgpl3, ANY,
            Copyleft.LIBRARY, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI, FSF, GPL_COMPATIBLE), 2, 6878, "LGPL-3.0.txt",
            "https://www.gnu.org/licenses/lgpl-3.0.html", "Qt, GMP", listOf(Res.string.license_note_lgpl_static),
        ),
        License(
            "EPL-2.0", "EPL-2.0", "Eclipse Public License 2.0", WEAK_COPYLEFT, setOf(CODE), Res.string.license_sum_epl, ANY, Copyleft.FILE,
            OPEN + setOf(NOTICE, DISCLOSE, PATENT, OSI, FSF), 2, 2130, "EPL-2.0.txt", "https://www.eclipse.org/legal/epl-2.0/",
            "Eclipse IDE, Jakarta EE, OpenJ9", listOf(Res.string.license_note_epl_secondary),
        ),
        License(
            "GPL-3.0", "GPL-3.0", "GNU General Public License v3.0", STRONG_COPYLEFT, setOf(CODE), Res.string.license_sum_gpl3, ANY,
            Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI, FSF, GPL_COMPATIBLE), 5, 5644, "GPL-3.0.txt",
            "https://www.gnu.org/licenses/gpl-3.0.html", "Bash, GIMP, Ansible", listOf(Res.string.license_note_gpl_saas),
        ),
        License(
            "GPL-2.0", "GPL-2.0", "GNU General Public License v2.0", STRONG_COPYLEFT, setOf(CODE), Res.string.license_sum_gpl2, ANY,
            Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, OSI, FSF), 3, 2910, "GPL-2.0.txt",
            "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html", "Linux, Git, WordPress, MySQL",
            listOf(Res.string.license_note_gpl2_or_later, Res.string.license_note_gpl_saas),
        ),
        License(
            "AGPL-3.0", "AGPL-3.0", "GNU Affero General Public License v3.0", STRONG_COPYLEFT, setOf(CODE), Res.string.license_sum_agpl, ANY,
            Copyleft.NETWORK, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI, FSF, GPL_COMPATIBLE), 3, 5535, "AGPL-3.0.txt",
            "https://www.gnu.org/licenses/agpl-3.0.html", "Mastodon, Nextcloud, Grafana, Ghostscript", listOf(Res.string.license_note_agpl_companies),
        ),
        License(
            "EUPL-1.2", "EUPL-1.2", "European Union Public Licence 1.2", STRONG_COPYLEFT, setOf(CODE), Res.string.license_sum_eupl, ANY,
            Copyleft.NETWORK, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI, FSF, GPL_COMPATIBLE), 2, 2212, "EUPL-1.2.txt",
            "https://interoperable-europe.ec.europa.eu/collection/eupl", "EUSurvey, Joinup",
        ),
        License(
            "BUSL-1.1", "BUSL-1.1", "Business Source License 1.1", SOURCE_AVAILABLE, setOf(CODE), Res.string.license_sum_busl, NON_PRODUCTION,
            Copyleft.NONE, OPEN + setOf(NOTICE, DELAYED), 3, 550, "BUSL-1.1.txt", "https://mariadb.com/bsl11/", "Terraform, Vault, MariaDB MaxScale",
            listOf(Res.string.license_note_busl_parameters, Res.string.license_note_not_open_source),
        ),
        License(
            "FSL-1.1-MIT", "FSL-1.1-MIT", "Functional Source License 1.1, MIT Future", SOURCE_AVAILABLE, setOf(CODE), Res.string.license_sum_fsl,
            NON_COMPETING, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT, DELAYED), 2, 662, "FSL-1.1-MIT.md", "https://fsl.software",
            "Sentry, Codecov", listOf(Res.string.license_note_fsl_apache, Res.string.license_note_not_open_source),
        ),
        License(
            "Elastic-2.0", "Elastic-2.0", "Elastic License 2.0", SOURCE_AVAILABLE, setOf(CODE), Res.string.license_sum_elastic, NO_HOSTING,
            Copyleft.NONE, OPEN + setOf(NOTICE, PATENT), 2, 614, "Elastic-2.0.txt", "https://www.elastic.co/licensing/elastic-license",
            "Elasticsearch, Kibana, Airbyte", listOf(Res.string.license_note_not_open_source),
        ),
        License(
            "SSPL-1.0", "SSPL-1.0", "Server Side Public License v1", SOURCE_AVAILABLE, setOf(CODE), Res.string.license_sum_sspl, ANY,
            Copyleft.NETWORK, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT), 2, 4765, "SSPL-1.0.txt",
            "https://www.mongodb.com/legal/licensing/server-side-public-license", "MongoDB, Graylog", listOf(Res.string.license_note_not_open_source),
        ),
        License(
            "LicenseRef-PolyForm-Shield-1.0.0", "PolyForm Shield", "PolyForm Shield License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_shield, NON_COMPETING, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT), 1, 903, "PolyForm-Shield-1.0.0.md",
            "https://polyformproject.org/licenses/shield/1.0.0", notes = listOf(Res.string.license_note_not_open_source),
        ),
        License(
            "LicenseRef-PolyForm-Perimeter-1.0.1", "PolyForm Perimeter", "PolyForm Perimeter License 1.0.1", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_perimeter, NON_COMPETING, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT), 1, 724,
            "PolyForm-Perimeter-1.0.1.md", "https://polyformproject.org/licenses/perimeter/1.0.1",
            notes = listOf(Res.string.license_note_not_open_source),
        ),
        License(
            "PolyForm-Small-Business-1.0.0", "PolyForm Small Business", "PolyForm Small Business License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_small, SMALL_BUSINESS, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT), 1, 693,
            "PolyForm-Small-Business-1.0.0.md", "https://polyformproject.org/licenses/small-business/1.0.0",
            notes = listOf(Res.string.license_note_not_open_source),
        ),
        License(
            "PolyForm-Noncommercial-1.0.0", "PolyForm Noncommercial", "PolyForm Noncommercial License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_noncommercial, NONE, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT), 2, 700,
            "PolyForm-Noncommercial-1.0.0.md", "https://polyformproject.org/licenses/noncommercial/1.0.0",
            notes = listOf(Res.string.license_note_noncommercial, Res.string.license_note_not_open_source),
        ),
        License(
            "LicenseRef-PolyForm-Internal-Use-1.0.0", "PolyForm Internal Use", "PolyForm Internal Use License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_internal, INTERNAL, Copyleft.NONE, setOf(MODIFY, PATENT), 1, 537, "PolyForm-Internal-Use-1.0.0.md",
            "https://polyformproject.org/licenses/internal-use/1.0.0",
        ),
        License(
            "LicenseRef-PolyForm-Free-Trial-1.0.0", "PolyForm Free Trial", "PolyForm Free Trial License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_trial, TRIAL, Copyleft.NONE, setOf(MODIFY, PATENT), 1, 510, "PolyForm-Free-Trial-1.0.0.md",
            "https://polyformproject.org/licenses/free-trial/1.0.0",
        ),
        License(
            "LicenseRef-PolyForm-Strict-1.0.0", "PolyForm Strict", "PolyForm Strict License 1.0.0", SOURCE_AVAILABLE, setOf(CODE),
            Res.string.license_sum_pf_strict, NONE, Copyleft.NONE, setOf(PATENT), 1, 562, "PolyForm-Strict-1.0.0.md",
            "https://polyformproject.org/licenses/strict/1.0.0", "Mobitool",
        ),
        License(
            "CC-BY-4.0", "CC BY 4.0", "Creative Commons Attribution 4.0 International", CREATIVE_COMMONS, setOf(CONTENT, DATA),
            Res.string.license_sum_cc_by, ANY, Copyleft.NONE, OPEN + setOf(NOTICE, STATE_CHANGES, FSF, GPL_COMPATIBLE), 5, 2550, "CC-BY-4.0.txt",
            "https://creativecommons.org/licenses/by/4.0/", "PLOS, eLife, Our World in Data", listOf(Res.string.license_note_cc_code),
        ),
        License(
            "CC-BY-SA-4.0", "CC BY-SA 4.0", "Creative Commons Attribution-ShareAlike 4.0 International", CREATIVE_COMMONS, setOf(CONTENT, DATA),
            Res.string.license_sum_cc_by_sa, ANY, Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES, FSF, GPL_COMPATIBLE), 5, 2744,
            "CC-BY-SA-4.0.txt", "https://creativecommons.org/licenses/by-sa/4.0/", "Wikipedia, Stack Overflow",
            listOf(Res.string.license_note_cc_by_sa_gpl, Res.string.license_note_cc_code),
        ),
        License(
            "CC-BY-ND-4.0", "CC BY-ND 4.0", "Creative Commons Attribution-NoDerivatives 4.0 International", CREATIVE_COMMONS, setOf(CONTENT, DATA),
            Res.string.license_sum_cc_by_nd, ANY, Copyleft.NONE, setOf(DISTRIBUTE, NOTICE), 2, 2516, "CC-BY-ND-4.0.txt",
            "https://creativecommons.org/licenses/by-nd/4.0/",
        ),
        License(
            "CC-BY-NC-4.0", "CC BY-NC 4.0", "Creative Commons Attribution-NonCommercial 4.0 International", CREATIVE_COMMONS, setOf(CONTENT, DATA),
            Res.string.license_sum_cc_by_nc, NONE, Copyleft.NONE, OPEN + setOf(NOTICE, STATE_CHANGES), 4, 2630, "CC-BY-NC-4.0.txt",
            "https://creativecommons.org/licenses/by-nc/4.0/", notes = listOf(Res.string.license_note_noncommercial),
        ),
        License(
            "CC-BY-NC-SA-4.0", "CC BY-NC-SA 4.0", "Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International", CREATIVE_COMMONS,
            setOf(CONTENT, DATA), Res.string.license_sum_cc_by_nc_sa, NONE, Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES), 4, 2825,
            "CC-BY-NC-SA-4.0.txt", "https://creativecommons.org/licenses/by-nc-sa/4.0/", "MIT OpenCourseWare",
            listOf(Res.string.license_note_noncommercial),
        ),
        License(
            "CC-BY-NC-ND-4.0", "CC BY-NC-ND 4.0", "Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International", CREATIVE_COMMONS,
            setOf(CONTENT, DATA), Res.string.license_sum_cc_by_nc_nd, NONE, Copyleft.NONE, setOf(DISTRIBUTE, NOTICE), 3, 2596,
            "CC-BY-NC-ND-4.0.txt", "https://creativecommons.org/licenses/by-nc-nd/4.0/", "TED Talks",
        ),
        License(
            "CERN-OHL-P-2.0", "CERN-OHL-P-2.0", "CERN Open Hardware Licence v2, Permissive", LicenseFamily.HARDWARE, setOf(HARDWARE),
            Res.string.license_sum_ohl_p, ANY, Copyleft.NONE, OPEN + setOf(NOTICE, PATENT, OSI), 1, 1313, "CERN-OHL-P-2.0.txt",
            "https://ohwr.org/cern_ohl_p_v2.txt",
        ),
        License(
            "CERN-OHL-W-2.0", "CERN-OHL-W-2.0", "CERN Open Hardware Licence v2, Weakly Reciprocal", LicenseFamily.HARDWARE, setOf(HARDWARE),
            Res.string.license_sum_ohl_w, ANY, Copyleft.FILE, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI), 1, 2134,
            "CERN-OHL-W-2.0.txt", "https://ohwr.org/cern_ohl_w_v2.txt", notes = listOf(Res.string.license_note_ohl_location),
        ),
        License(
            "CERN-OHL-S-2.0", "CERN-OHL-S-2.0", "CERN Open Hardware Licence v2, Strongly Reciprocal", LicenseFamily.HARDWARE, setOf(HARDWARE),
            Res.string.license_sum_ohl_s, ANY, Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, PATENT, OSI), 2, 1977,
            "CERN-OHL-S-2.0.txt", "https://ohwr.org/cern_ohl_s_v2.txt", "White Rabbit", listOf(Res.string.license_note_ohl_location),
        ),
        License(
            "OFL-1.1", "OFL-1.1", "SIL Open Font License 1.1", FONTS_DATA, setOf(FONT), Res.string.license_sum_ofl, ANY, Copyleft.PROJECT,
            OPEN + setOf(NOTICE, OSI, FSF), 5, 635, "OFL-1.1.txt", "https://openfontlicense.org",
            "Google Fonts, Fira, Source Sans, JetBrains Mono, Inter",
        ),
        License(
            "ODbL-1.0", "ODbL-1.0", "Open Data Commons Open Database License 1.0", FONTS_DATA, setOf(DATA), Res.string.license_sum_odbl, ANY,
            Copyleft.PROJECT, OPEN + setOf(NOTICE, DISCLOSE, FSF), 3, 4065, "ODbL-1.0.txt", "https://opendatacommons.org/licenses/odbl/1-0/",
            "OpenStreetMap", listOf(Res.string.license_note_odbl_contents),
        ),
        License(
            "CDLA-Permissive-2.0", "CDLA-Permissive-2.0", "Community Data License Agreement Permissive 2.0", FONTS_DATA, setOf(DATA),
            Res.string.license_sum_cdla, ANY, Copyleft.NONE, OPEN + NOTICE, 1, 367, "CDLA-Permissive-2.0.txt", "https://cdla.dev/permissive-2-0/",
        ),
        License(
            "GFDL-1.3", "GFDL-1.3", "GNU Free Documentation License v1.3", FONTS_DATA, setOf(CONTENT), Res.string.license_sum_gfdl, ANY,
            Copyleft.PROJECT, OPEN + setOf(NOTICE, STATE_CHANGES, DISCLOSE, FSF), 1, 3689, "GFDL-1.3.txt", "https://www.gnu.org/licenses/fdl-1.3.html",
            "GNU manuals", listOf(Res.string.license_note_gfdl_invariant),
        ),
        License(
            "LicenseRef-Proprietary", "©", "All rights reserved", PROPRIETARY, LicenseSubject.entries.toSet(), Res.string.license_sum_proprietary,
            NONE, Copyleft.NONE, emptySet(), 5, 0, "LicenseRef-Proprietary.txt", "", notes = listOf(Res.string.license_note_github_no_license),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): License? = byId[id]

    fun value(license: License, feature: LicenseFeature): FeatureValue {
        fun yesNo(flag: LicenseFlag) = if (flag in license) FeatureValue(Mark.YES, Res.string.license_yes) else FeatureValue(Mark.NO, Res.string.license_no)
        return when (feature) {
            LicenseFeature.COMMERCIAL -> FeatureValue(
                when (license.commercial) {
                    ANY -> Mark.YES
                    NONE -> Mark.NO
                    else -> Mark.PARTLY
                },
                license.commercial.cell,
            )
            LicenseFeature.MODIFY -> yesNo(MODIFY)
            LicenseFeature.DISTRIBUTE -> yesNo(DISTRIBUTE)
            LicenseFeature.PATENT -> yesNo(PATENT)
            LicenseFeature.NOTICE -> yesNo(NOTICE)
            LicenseFeature.STATE_CHANGES -> yesNo(STATE_CHANGES)
            LicenseFeature.DISCLOSE -> yesNo(DISCLOSE)
            LicenseFeature.SAME_LICENSE -> FeatureValue(
                when (license.copyleft.level) {
                    0 -> Mark.NO
                    1 -> Mark.PARTLY
                    else -> Mark.YES
                },
                license.copyleft.cell,
            )
            LicenseFeature.OSI -> yesNo(OSI)
            LicenseFeature.FSF -> yesNo(FSF)
            LicenseFeature.GPL -> yesNo(GPL_COMPATIBLE)
        }
    }

    fun search(query: String, family: LicenseFamily?): List<License> {
        val q = query.trim()
        if (q.isEmpty()) return all.filter { family == null || it.family == family }
        return all.filter { license ->
            license.id.contains(q, ignoreCase = true) || license.short.contains(q, ignoreCase = true) ||
                license.name.contains(q, ignoreCase = true) || license.usedBy.contains(q, ignoreCase = true) ||
                license.summary.matches(q.lowercase()) || license.family.title.matches(q.lowercase())
        }
    }
}

enum class PickCommercial(val title: StringResource) {
    ANY(Res.string.license_pick_commercial_any),
    LIMITED(Res.string.license_pick_commercial_limited),
    SMALL(Res.string.license_pick_commercial_small),
    NONCOMMERCIAL(Res.string.license_pick_commercial_none),
    CONTRACT(Res.string.license_pick_commercial_contract),
    ;

    fun accepts(license: License): Boolean = when (this) {
        ANY -> license.commercial == Commercial.ANY
        LIMITED -> license.commercial in setOf(NON_COMPETING, NO_HOSTING, NON_PRODUCTION)
        SMALL -> license.commercial == SMALL_BUSINESS
        NONCOMMERCIAL -> license.commercial == NONE && license.family != PROPRIETARY
        CONTRACT -> license.family == PROPRIETARY || license.commercial == INTERNAL || license.commercial == TRIAL
    }

    // copyleft, credit and changes only make sense where others get to build on the work
    val openQuestions: Boolean get() = this == ANY || this == NONCOMMERCIAL
}

enum class PickCopyleft(val level: Int, val title: StringResource) {
    NONE(0, Res.string.license_pick_copyleft_none),
    WEAK(1, Res.string.license_pick_copyleft_weak),
    STRONG(2, Res.string.license_pick_copyleft_strong),
    NETWORK(3, Res.string.license_pick_copyleft_network),
}

enum class LicensePriority(val title: StringResource) {
    POPULAR(Res.string.license_priority_popular),
    SHORT(Res.string.license_priority_short),
    PROTECTION(Res.string.license_priority_protection),
}

data class LicenseNeeds(
    val subject: LicenseSubject = CODE,
    val commercial: PickCommercial = PickCommercial.ANY,
    val copyleft: PickCopyleft = PickCopyleft.NONE,
    val changes: Boolean = true,
    val credit: Boolean = true,
    val patent: Boolean = false,
    val delayed: Boolean = false,
    val gpl: Boolean = false,
    val priority: LicensePriority = LicensePriority.POPULAR,
) {
    val asksPatent: Boolean get() = subject == CODE || subject == HARDWARE
    val asksDelayed: Boolean get() = subject == CODE && commercial == PickCommercial.LIMITED
    val asksGpl: Boolean get() = subject == CODE && commercial == PickCommercial.ANY
}

class LicenseMatch(val license: License, val score: Int, val copyleftGap: Int)

object LicensePicker {
    fun commercialOptions(subject: LicenseSubject): List<PickCommercial> =
        PickCommercial.entries.filter { option -> Licenses.all.any { subject in it.subjects && option.accepts(it) } }

    fun copyleftOptions(subject: LicenseSubject, commercial: PickCommercial): List<PickCopyleft> {
        val levels = Licenses.all.filter { subject in it.subjects && commercial.accepts(it) && MODIFY in it }.map { it.copyleft.level }.toSet()
        return PickCopyleft.entries.filter { it.level in levels }
    }

    fun simplicity(words: Int): Int = when {
        words < 300 -> 5
        words < 1000 -> 4
        words < 2500 -> 3
        words < 5000 -> 2
        else -> 1
    }

    private fun protection(license: License): Int =
        license.copyleft.level + (if (PATENT in license) 2 else 0) + listOf(NOTICE, STATE_CHANGES, DISCLOSE).count { it in license }

    fun pick(needs: LicenseNeeds): List<LicenseMatch> = Licenses.all.mapNotNull { license ->
        val open = needs.commercial.openQuestions
        val gap = if (open && needs.changes) abs(license.copyleft.level - needs.copyleft.level) else 0
        val excluded = needs.subject !in license.subjects ||
            !needs.commercial.accepts(license) ||
            open && needs.changes != MODIFY in license ||
            open && needs.credit && DISTRIBUTE in license && NOTICE !in license ||
            gap >= 2 ||
            needs.asksPatent && needs.patent && PATENT !in license ||
            needs.asksDelayed && needs.delayed && DELAYED !in license ||
            needs.asksGpl && needs.gpl && GPL_COMPATIBLE !in license
        if (excluded) return@mapNotNull null
        val simple = simplicity(license.words.takeIf { it > 0 } ?: 1)
        var score = license.popularity * 2 + simple
        score += when (needs.priority) {
            LicensePriority.POPULAR -> license.popularity * 3
            LicensePriority.SHORT -> simple * 3
            LicensePriority.PROTECTION -> protection(license) * 2
        }
        score -= gap * 15
        if (open && !needs.credit && NOTICE in license) score -= 12
        if (needs.commercial == PickCommercial.ANY && OSI in license) score += 2
        LicenseMatch(license, score, gap)
    }.sortedByDescending { it.score }
}
