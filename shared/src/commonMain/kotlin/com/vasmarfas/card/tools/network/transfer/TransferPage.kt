package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.formatBytes

class PageText(
    val lang: String,
    val title: String,
    val forYou: String,
    val download: String,
    val sendTo: String,
    val choose: String,
    val drop: String,
    val done: String,
    val failed: String,
)

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun transferPage(alias: String, files: List<Pair<String, Long>>, text: PageText): String = buildString {
    append("<!doctype html><html lang=\"").append(text.lang).append("\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>").append(html(text.title)).append(" · ").append(html(alias)).append("</title>")
    append(
        """
        <style>
        :root{--bg:#F0EAE1;--card:#fff;--ink:#1d2a2d;--muted:#5d6b6e;--accent:#326773;--line:#ddd3c6}
        @media (prefers-color-scheme:dark){:root{--bg:#141b1d;--card:#1f292c;--ink:#e8e4de;--muted:#9aa7a9;--accent:#6fb3c1;--line:#2e3a3d}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
        main{max-width:560px;margin:0 auto;padding:24px 16px 48px}
        h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin:0 0 20px}
        section{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;margin-bottom:16px}
        h2{font-size:16px;margin:0 0 12px}
        .row{display:flex;align-items:center;gap:12px;padding:10px 0;border-top:1px solid var(--line)}.row:first-of-type{border-top:0}
        .name{flex:1;min-width:0;overflow-wrap:anywhere}.size{color:var(--muted);font-size:13px}
        a.btn,label.btn{display:inline-block;background:var(--accent);color:#fff;text-decoration:none;border-radius:999px;padding:10px 18px;font-weight:600;cursor:pointer}
        #zone{border:2px dashed var(--line);border-radius:14px;padding:24px;text-align:center}#zone.over{border-color:var(--accent)}
        #zone p{color:var(--muted);margin:10px 0 0}input[type=file]{display:none}
        progress{width:100%;height:8px;accent-color:var(--accent)}.state{font-size:13px;color:var(--muted)}
        </style></head><body><main>
        """.trimIndent(),
    )
    append("<h1>").append(html(text.title)).append("</h1><p class=\"sub\">").append(html(alias)).append("</p>")
    if (files.isNotEmpty()) {
        append("<section><h2>").append(html(text.forYou)).append("</h2>")
        files.forEachIndexed { index, (name, size) ->
            append("<div class=\"row\"><div class=\"name\">").append(html(name)).append("<div class=\"size\">").append(formatBytes(size)).append("</div></div>")
            append("<a class=\"btn\" href=\"file/").append(index).append('/').append(percentEncode(name)).append("\" download>").append(html(text.download)).append("</a></div>")
        }
        append("</section>")
    }
    append("<section><h2>").append(html(text.sendTo.replace("%1\$s", alias))).append("</h2>")
    append("<div id=\"zone\"><label class=\"btn\">").append(html(text.choose)).append("<input id=\"pick\" type=\"file\" multiple></label><p>").append(html(text.drop)).append("</p></div>")
    append("<div id=\"list\"></div></section></main>")
    append("<script>const DONE=").append(jsString(text.done)).append(",FAILED=").append(jsString(text.failed)).append(";")
    append(
        """
        const list=document.getElementById('list'),zone=document.getElementById('zone'),queue=[];let busy=false;
        function add(files){for(const f of files){const row=document.createElement('div');row.className='row';
        const box=document.createElement('div');box.className='name';box.textContent=f.name;
        const bar=document.createElement('progress');bar.max=1;bar.value=0;const st=document.createElement('div');st.className='state';
        box.append(bar,st);row.append(box);list.prepend(row);queue.push({f,bar,st});}next();}
        function next(){if(busy||!queue.length)return;busy=true;const {f,bar,st}=queue.shift();const x=new XMLHttpRequest();
        x.open('POST','upload?name='+encodeURIComponent(f.name));x.upload.onprogress=e=>{if(e.lengthComputable)bar.value=e.loaded/e.total};
        x.onloadend=()=>{const ok=x.status===200;bar.value=ok?1:0;st.textContent=ok?DONE:FAILED;busy=false;next()};x.send(f);}
        document.getElementById('pick').onchange=e=>{add(e.target.files);e.target.value=''};
        zone.ondragover=e=>{e.preventDefault();zone.classList.add('over')};zone.ondragleave=()=>zone.classList.remove('over');
        zone.ondrop=e=>{e.preventDefault();zone.classList.remove('over');add(e.dataTransfer.files)};
        </script></body></html>
        """.trimIndent(),
    )
}

private fun jsString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""
