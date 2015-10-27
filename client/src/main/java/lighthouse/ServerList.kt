package lighthouse

import lighthouse.utils.I18nUtil.tr

/**
 * Hard-coded list of project servers so the app can randomly pick between them and load balance the work.
 */
public object ServerList {
    enum class SubmitType { EMAIL, WEB }
    class Entry(val hostName: String, val submitAddress: String, val instructions: String, val submitType: SubmitType)

    val servers = listOf(
            Entry("vinumeris.com", "project-hosting@vinumeris.com", tr("Submission via email. Project must be legal under Swiss and UK law."), SubmitType.EMAIL),
            Entry("lighthouse.onetapsw.com", "lighthouse-projects@onetapsw.com", tr("Submission via email. Project must be legal under US law."), SubmitType.EMAIL),
            Entry("lighthouseprojects.io", "projects@lighthouseprojects.io", tr("Submission via email. Project must be legal under New Zealand law."), SubmitType.EMAIL),
            Entry("lighthouse.bitseattle.com", "https://lighthouse.bitseattle.com/lighthouse-projects/upload/", tr("Submission via the web. Project must be legal under US law."), SubmitType.WEB),
            Entry("server.lightlist.io", "https://www.lightlist.io/projects/new", tr("Submission via the web. Project must be legal under US law."), SubmitType.WEB)
    )
    @JvmField val hostnameToServer: Map<String, Entry> = servers.map { Pair(it.hostName, it) }.toMap()

    fun pickRandom(): Entry = servers[(Math.random() * servers.size).toInt()]
}
