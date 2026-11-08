package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// throwaway keys from ssh-keygen and openssl. The armour is put together at run time, so that secret scanners
// do not take the files for leaked keys
class SshKeysTest {
    private fun pem(label: String, body: String, headers: List<String>) = buildString {
        appendLine("-----BEGIN $label-----")
        headers.forEach { appendLine(it) }
        if (headers.isNotEmpty()) appendLine()
        appendLine(body.trimIndent())
        append("-----END $label-----")
    }

    private fun check(label: String, body: String, publicBlob: String, passphrase: String, vararg headers: String) {
        val key = parsePrivateKey(pem(label, body, headers.toList()), passphrase)
        assertEquals(publicBlob, Base64.encode(key.publicBlob))
        val data = "exchange hash".encodeToByteArray()
        for (algorithm in key.algorithms(null)) {
            val signature = key.sign(algorithm, data)
            assertTrue(verifyHostSignature(algorithm, key.publicBlob, data, signature), algorithm)
            assertFalse(verifyHostSignature(algorithm, key.publicBlob, "other hash".encodeToByteArray(), signature), algorithm)
        }
    }

    @Test
    fun ed25519OpenSsh() = check(
        "OPENSSH PRIVATE KEY",
        """
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACDBH0QJR/IahctQB97bgFyqiEuk2XdVnGKN8brK1lnIMAAAAIj2b05e9m9O
            XgAAAAtzc2gtZWQyNTUxOQAAACDBH0QJR/IahctQB97bgFyqiEuk2XdVnGKN8brK1lnIMA
            AAAEBcoFGLWKB1MIh0iwAgmyKLwwMPa0b0p+JapCNuwqFK/MEfRAlH8hqFy1AH3tuAXKqI
            S6TZd1WcYo3xusrWWcgwAAAABHRlc3QB
        """,
        "AAAAC3NzaC1lZDI1NTE5AAAAIMEfRAlH8hqFy1AH3tuAXKqIS6TZd1WcYo3xusrWWcgw",
        "",
    )

    @Test
    fun ed25519OpenSshBcrypt() = check(
        "OPENSSH PRIVATE KEY",
        """
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABADpqBVQ0
            6YQ5F8qb2HpLLnAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIM6g39Dfmmwfqj2q
            CDGAmNXGOfvT0die1q3xghmUwwGkAAAAkDoOUXYCtYSdyVKOUoxEBP85eHadUh1UU/khqW
            dWmKzNX4DKEXQDkYkV4i2loDXP7PLQii1bM86DBOYez4Y3KHEDGYCsJG/eOI6MwfBnjV8C
            zlwp44OMXb55ukBBRzBkxTmLrmDC74PNlwj/mAYE8obdcHV9qWmYIzWTzXcyASol8NSPB/
            Ry/FJ/itsQKXSvUA==
        """,
        "AAAAC3NzaC1lZDI1NTE5AAAAIM6g39Dfmmwfqj2qCDGAmNXGOfvT0die1q3xghmUwwGk",
        "test-passphrase",
    )

    @Test
    fun ed25519Pkcs8() = check(
        "PRIVATE KEY",
        """
            MC4CAQAwBQYDK2VwBCIEIE09LdqN93Otf5w/XO6/H4ps0C+39IaVwwNCjIpTnOBg
        """,
        "AAAAC3NzaC1lZDI1NTE5AAAAIAdtyy2+7H+Xv9LdA47IEJDHCK/cjNj/1uziK6ad6hMH",
        "",
    )

    @Test
    fun rsaOpenSsh() = check(
        "OPENSSH PRIVATE KEY",
        """
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
            NhAAAAAwEAAQAAAQEAwcpQmET+ruqax2nUBaUtfEe8VDFvgd8Lq7HX/rMlCb/fXovfgHmS
            sYt8zIyyh2eSG3P92lXlg9sN6r9gfTftldFlkLVaGvRK0M8yP0x8Hc4QxKrm/QF+ucOAs/
            N3Ak1OP6h/W95n2ETNy7iuv1CgWL+FkWra41VHGhrYoe9cls+SKyWphkQFjVJJO1VH1ZXf
            3gQ9/jzOgPjl20/6oNJPMLVXELDB+8LPl/K/KrixBCepmGUb3DprqyLtXEemkLhfYDkaVc
            rVrttBqm9Sa+2YHFK1uIZn0tYClMEXA02eSLXrKn3EdfWotbQL7s2wDvvbb/RdjI5i1/9/
            bfs2KmWdgQAAA7jD8E8Lw/BPCwAAAAdzc2gtcnNhAAABAQDBylCYRP6u6prHadQFpS18R7
            xUMW+B3wursdf+syUJv99ei9+AeZKxi3zMjLKHZ5Ibc/3aVeWD2w3qv2B9N+2V0WWQtVoa
            9ErQzzI/THwdzhDEqub9AX65w4Cz83cCTU4/qH9b3mfYRM3LuK6/UKBYv4WRatrjVUcaGt
            ih71yWz5IrJamGRAWNUkk7VUfVld/eBD3+PM6A+OXbT/qg0k8wtVcQsMH7ws+X8r8quLEE
            J6mYZRvcOmurIu1cR6aQuF9gORpVytWu20Gqb1Jr7ZgcUrW4hmfS1gKUwRcDTZ5Itesqfc
            R19ai1tAvuzbAO+9tv9F2MjmLX/39t+zYqZZ2BAAAAAwEAAQAAAP8YDZezTqnN5cPdIuWO
            Gp83epZSXOoNbjULB1sn4e4KJ8Axzzd09G7T9EKpOoChdD/n6GqGjxYA9IKygGgZiR36ja
            /T8xTo0FzFtIuOdilxozVLM9GePAreqivVw7WIM5z8Vo2reXXdaHmir5zNyKDrL9pqq9I/
            WHZazYcJLAyLkacRbY9BKDEPcvVrcqmI4IFZ3C8G7hVDnyMjIRQ9X0mL0D+l6nqB1Yv9MH
            4VkmpS77adv3McyKIjAi3lSTB12jMS5CZxcdold8LWJz7e1Sg9EcMYcPOQYoNePR2i7D83
            7qju+r26PqxA4zKYPCm0DHwjC9100GfkNclRKy5KlIEAAACARLQKGsVLVdaU72DjMQ8pNp
            ygW9byZVI8701rtDOYpChFLDBXABAZUhdVK0VKpnV3tpFLK37c/IGQuBDLw+UDQ8qXidUV
            ZipbSTgSBmAyGhfj+txsCm4SwSeLbG/3TGtrXUe2VQXjxevwjJpO77CYYcceaPz2B6r8b7
            FWSaBr8FoAAACBAOc4REbuzHV2UuMvu6N6xh2Fxf7YMPZmBbcgXSr2ZGTZq+toUQELLRTk
            bInuHlteDL0yULBtvavIT8Sb/wk2qMsbZZmBgbn/eb4PKwOv/WT7m+Qqj9UfNAL82PjWa/
            dvx1kbVE0M6AOKVWRlPYN8tsmDdVTelKDElPWNFOGrr1RhAAAAgQDWjyJry+ctqLJN7O6V
            Y48vfPp+GAm/lF24bXjSvFhY+JEECVbgx2TAwiMO+mwDGefdrK/JAds/az/Ldj3cBbwdBX
            Rxcjyoi/jrTQBfdjjjzOmR/R5G7VlFDu2vmLsdJgpWtzqW+fweb5D/5sHc55OECnTygJ+g
            mKAXqHZcEXTdIQAAAAR0ZXN0
        """,
        "AAAAB3NzaC1yc2EAAAADAQABAAABAQDBylCYRP6u6prHadQFpS18R7xUMW+B3wursdf+syUJv99ei9+AeZKxi3zMjLKHZ5Ibc/3aVeWD2w3qv2B9N+2V0WWQtVoa9ErQzzI/THwdzhDEqub9AX65w4Cz83cCTU4/qH9b3mfYRM3LuK6/UKBYv4WRatrjVUcaGtih71yWz5IrJamGRAWNUkk7VUfVld/eBD3+PM6A+OXbT/qg0k8wtVcQsMH7ws+X8r8quLEEJ6mYZRvcOmurIu1cR6aQuF9gORpVytWu20Gqb1Jr7ZgcUrW4hmfS1gKUwRcDTZ5ItesqfcR19ai1tAvuzbAO+9tv9F2MjmLX/39t+zYqZZ2B",
        "",
    )

    @Test
    fun rsaPkcs1Encrypted() = check(
        "RSA PRIVATE KEY",
        """
            W+QL+n9B1Oiaseyuxl68rn6dwh4kqWjtO52xuVqQsM70QKKrtt5xqxRpK4twjWqj
            8XsadBOiK3ixoU1s5WgiI4SZdDpjbpeG6Rs1CeQHs0bgNKfUBFoiZ5naansEXQzB
            khVdl35zz9Ya7x1wJVYOTK6NbYzdJgU+7Y5zD8ikMUSl0tCT/dQ++UIbEw98QW0O
            Vmfs1nxSqJqxyZT2f93kwRt+qhrfHm65F8n5d+TY8wUwJyVPXrG+QENACNNTulVE
            JZYizwIPb8ja+Ac8bpkR/VIELZa0kBdSnmOl9hsKCwqvGXgJdYdkixL6UAMs4kCi
            NNOzaH71qPvVgIxvP2JmwKcFhlEjJXHg4LDF6zAHOs0TuTq8vzuC1pfkKNZ7o8sK
            nObXakxnMFwdjPG2j4rgRTxaZsvdW1fOXS5oJhf4JjBV6EC5zvq/Xm4iluXTGydl
            fmYUResR9PLy12mWm2i+YHPwHXwwgxyPQ9rtz7Qc1j0caDQHljgR973qRTYKZMmf
            mrSKOrfFFY+yM3X0GOOPLVOPHpy1bS6KOKeergm8pzoy+jaAcC6uH7DvnyH65xTL
            qWocJXlJtCly0QA3mL7fkg5blyeAQu2FTL061VngXqeGw415hjSSPlvVa44mqTod
            uIUdP2te8cdX0oQxSbfAADZK/lJ2Ws4fvjC2xvQsv5u8xWH7cacuZcwRgThm9Wzh
            uuKm13VsOEJrReeP5oPDc42yI04spbm4BIJoS6/mj70Nu3tAX0WncKyx1g5Rpxsb
            b/wzxP4qQWxH+uCKEmqGC+TBKUZ5BVrP8y0KPuxPNiQvHgalBy2zTt/8Airax7tC
            mL463XwhrIXwufxnTUoR/66g5CT2f3M7yvL0UamjH2moDbvymPm+b/ohaLa0xcWp
            ToMkTG2yr4e2ce8I46sybM0WCeLA2u7UNSLcmJZVbFWTVzYeKrDNHBm0zlHNDdMP
            7EFP1X/tgx8yeG+2B6EWXBdvb+VSGdh45mJegAp608uW1D5Ol1j+2AV7Sn03Bl5a
            iKy1IFWcK8ejHhUxHm+fTDX+IfVq5288kAPMdlJJdaVi82Y1rUUmm0HFwiYJTTgH
            V+ZRA9MRhaTllxEDrHDhag3GhZacx9LbvbcSyYX7CjG8Qakyiuyb57ncNK1elU04
            CDY4KDypIpBfCs20YCnWJToi02dwe78i/bK6IvQb43phe3KjHCaXuGZi92jQ/vgK
            z00qBmoWp9UxEwlamhZagUdK1i9e+Uo/mUXHnwBjSMQmjsAjjeJ/lcwFPvC9jDct
            2iWenBdA9Y65GDqLQGgg6eY+zav1ha6V+VI/httLLZ4mwdZchG7d2rSt5QB9XeB/
            o76BJhTQ8TremLvAd69Dwlj0JLS8taOFL0QbOtkVnwuf95qVsYzcMYEYIZ7lqRwk
            yPCOrEB8UGHVdGTcW8NamUsaov4ZtiQWYP4Uyk0ZNe0lB7fU4mI0uN7jGVYKq/R7
            6YCTrENXsAMfuUqLOiG8FP6BUUOLqTIpeq1E4+z/SRQscFQvGvXJPOYFleJAAvBY
            cM3FH9cyWfJQm4U8huObFHaUhGcNmDl+eqRnZGXiupc9bWua6/hJrCpNOWgFn1qV
        """,
        "AAAAB3NzaC1yc2EAAAADAQABAAABAQC/MYjmkqP85Rgi0JP5yFzFWEp9d6lpaPNioySwgrdxq3ljiKS8hPO2AKaJi/sWr15drRK0VDDGuHBY7g1tusLFX1bd202dMYgAMJRvGmz/be5qGHFGck9SIJrlXPQZ8VeXdlKfe5QUB9/alI/5oXswMIbJTxqZAz97BtxcgtJB5nz3J4v72EYyH+69lPVg3CCC+VWhfSogY+FdwQ4zA5q/5aHemW5wxxR1JcDtbjAUoLXMCMVFBSnQ9TbIzrx6rS7wmINmcBAt34R0YfTY8vBB2YeeSuqUmvSDDvKutdbl5IxtkIu4ye4MxxgySAzwJN6qgTi06pkhzw2rI4CUY9lz",
        "test-passphrase", "Proc-Type: 4,ENCRYPTED", "DEK-Info: AES-128-CBC,9A1F23D61053CF7CB9D900C5FC0A5B87",
    )

    @Test
    fun rsaPkcs8Encrypted() = check(
        "ENCRYPTED PRIVATE KEY",
        """
            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
        """,
        "AAAAB3NzaC1yc2EAAAADAQABAAABAQDI113Cboe8vskVarrUPZzqbRFabQxzhkJG12rePmYm13fSF6zNliIfwGZ6G8+/glxyhASofwnK1wwmqz4iMvdwEc/ISvCLU5TyegWkqDW+reAF+JTVTlfRDXxkJTd33IeSadMp846/iqTIdk6JEx2AUT2LVbfZ4qXJ1N/7JhNPAmA7Jd2STuVZkiRTphGfeMY377CBd17KnUM91bJ9yFNTr3rSuBEcnaq6TtV3sDwlUMoDWZLjS6u8bu1wWEFdIiorRE3Cj22YnJkyrM5UjlVQC+0NuRw6RuaucDsFATBGzXUBcLtYs9JYxEcdu+NkNXDW8TD/Pz2PHnsRfM577lBb",
        "test-passphrase",
    )

    @Test
    fun ecdsa256OpenSsh() = check(
        "OPENSSH PRIVATE KEY",
        """
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAaAAAABNlY2RzYS
            1zaGEyLW5pc3RwMjU2AAAACG5pc3RwMjU2AAAAQQQyPGMMONG0v0Li3E/AIwAFfVka4HTn
            Z+Br/jEooxeinDh+md3Cs58ln02nCNmY3SVjThb9Jm+KpQKXx8KmPXtOAAAAoAXumnYF7p
            p2AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBDI8Yww40bS/QuLc
            T8AjAAV9WRrgdOdn4Gv+MSijF6KcOH6Z3cKznyWfTacI2ZjdJWNOFv0mb4qlApfHwqY9e0
            4AAAAhAL/g+/rNJfCSJ2ku7VuwqMttqfuf0qbflnnO+ywdmejXAAAABHRlc3QBAgM=
        """,
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBDI8Yww40bS/QuLcT8AjAAV9WRrgdOdn4Gv+MSijF6KcOH6Z3cKznyWfTacI2ZjdJWNOFv0mb4qlApfHwqY9e04=",
        "",
    )

    @Test
    fun ecdsa256OpenSshGcm() = check(
        "OPENSSH PRIVATE KEY",
        """
            b3BlbnNzaC1rZXktdjEAAAAAFmFlczI1Ni1nY21Ab3BlbnNzaC5jb20AAAAGYmNyeXB0AA
            AAGAAAABD+rugGAeYSje2R78uKjDYQAAAAGAAAAAEAAABoAAAAE2VjZHNhLXNoYTItbmlz
            dHAyNTYAAAAIbmlzdHAyNTYAAABBBBx/KfFbydvms0GZsmWwgJa3Hm8qbJ5dnM0+hq7FdW
            nao8fm4bP28PMxQeF7uR9x3g9drirDO0A+EjDTljIGtmMAAACg3HRs2pwYFMwP38rmYa97
            iGeDtJ3l+ZT0H7IdEmQhBnwmG6WUwCVnj3Mx0XBnli/1Tcjg1/06EjTYxbKiYTK+cNywU5
            KgtChuoKGCDjhkMpon7oC7Yr+jxw1ss2394rsPaqqZ5/P+skA8n/Hk8tvav+vrv0Q3ckzA
            TMRgMwJ9rI+5rR9MssrI425SbB62s6JVXD8Q23zKKwSikLVTeNt5I/aYTHz9D3uoI8CYnr
            L35Mc=
        """,
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBx/KfFbydvms0GZsmWwgJa3Hm8qbJ5dnM0+hq7FdWnao8fm4bP28PMxQeF7uR9x3g9drirDO0A+EjDTljIGtmM=",
        "test-passphrase",
    )

    @Test
    fun ecdsa384Sec1() = check(
        "EC PRIVATE KEY",
        """
            MIGkAgEBBDCqkbOyL6zXd0+Ysj78gyVolXkKlaf9bGEXv2UUnkuSBEUC/CDFcpMB
            wy+umfcW6eagBwYFK4EEACKhZANiAATfhuq1NLNkZf5m+SZPF/eFT6Vjf8rAwhwO
            08T+VPfFCDybW5CxlzfudTQh4HUCCz2wmbrcmcNXr3d+q6TujcBTEsUtsHrSlCj9
            Sn8NAZIK3kHAsdqXPFHqtrYHfIjN8Fc=
        """,
        "AAAAE2VjZHNhLXNoYTItbmlzdHAzODQAAAAIbmlzdHAzODQAAABhBN+G6rU0s2Rl/mb5Jk8X94VPpWN/ysDCHA7TxP5U98UIPJtbkLGXN+51NCHgdQILPbCZutyZw1evd36rpO6NwFMSxS2wetKUKP1Kfw0BkgreQcCx2pc8Ueq2tgd8iM3wVw==",
        "",
    )

    @Test
    fun ecdsa521Pkcs8Encrypted() = check(
        "ENCRYPTED PRIVATE KEY",
        """
            MIIBZTBfBgkqhkiG9w0BBQ0wUjAxBgkqhkiG9w0BBQwwJAQQ5rDoslt89kiF3/Xf
            5DIKYAICCAAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEAQIEEFmOGiZn9tMBGR8c
            +z/00gEEggEABJ83I1r55JJ2OqBdSerfPl2qdYyekCLF14CxeG03x+ZXR7j3Q+ap
            X9XCqZI+Ac8kyuZAoXTYfqWROufcwGgZkeyp5sUtGYned2HPAP5jCCUfulmgLLjU
            EBqZfcUzpIvygpB/S+rwLRiwA6FID6MnKA+c/pFv3LkXQrLBGnVdzeK1OCFNh0d8
            PqoWXA8nBJBgUkiYU/VJXGFbZS/eaWONIhJOCUC1TGWfd6AyaKnNT1FtARK31iTS
            b8tRhoiUiMyodII2X4vAVCavyFRktHVwCR6krPC/bB0okTwfFKv4hy/4/llIHDwd
            2144DNhZ+nYvSI464nqI9ONurjw0lZKM2Q==
        """,
        "AAAAE2VjZHNhLXNoYTItbmlzdHA1MjEAAAAIbmlzdHA1MjEAAACFBAHAdSwFbM3dN78H7vMmbVlss8YOgYIrZ5qirz2o2d/umedSm1EG6COsabG4O/d6tvaMnoTwuTWM0fjBuj+0rL8/ywDzKWkv52a5WeLNDXngjq8TUA9tABJ0keZADj1YJ8RK1frD0z1GZAAs3y4pfhei30zqTXAJKbIW+MaZkbLRd+Tw+Q==",
        "test-passphrase",
    )

    @Test
    fun wrongPassphraseIsAKeyError() {
        val (label, body) = ED25519_ENCRYPTED
        assertFailsWith<NetException> { parsePrivateKey(pem(label, body, emptyList()), "wrong") }
    }

    @Test
    fun publicKeyFileIsAKeyError() {
        assertFailsWith<NetException> { parsePrivateKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA test", "") }
    }

    private companion object {
        val ED25519_ENCRYPTED = "OPENSSH PRIVATE KEY" to """
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABADpqBVQ0
            6YQ5F8qb2HpLLnAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIM6g39Dfmmwfqj2q
            CDGAmNXGOfvT0die1q3xghmUwwGkAAAAkDoOUXYCtYSdyVKOUoxEBP85eHadUh1UU/khqW
            dWmKzNX4DKEXQDkYkV4i2loDXP7PLQii1bM86DBOYez4Y3KHEDGYCsJG/eOI6MwfBnjV8C
            zlwp44OMXb55ukBBRzBkxTmLrmDC74PNlwj/mAYE8obdcHV9qWmYIzWTzXcyASol8NSPB/
            Ry/FJ/itsQKXSvUA==
        """
    }
}
