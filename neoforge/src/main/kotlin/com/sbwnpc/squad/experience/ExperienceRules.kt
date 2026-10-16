package com.sbwnpc.squad.experience

import com.sbwnpc.squad.config.SquadConfig
import com.sbwnpc.squad.npc.NpcRank
import java.util.UUID

object ExperienceRules {
    private val firstNames = listOf(
        "Alex", "Sam", "Chris", "Max", "Victor", "Leon", "Daniel", "Nick",
        "Eli", "Mark", "Oscar", "Finn", "Robin", "James", "Anton", "Ben",
        "Adam", "Aaron", "Adrian", "Alan", "Albert", "Andrew", "Anthony", "Arthur",
        "Austin", "Blake", "Bradley", "Brandon", "Brent", "Brian", "Bruce", "Caleb",
        "Cameron", "Carl", "Carter", "Charles", "Chase", "Clayton", "Cody", "Colin",
        "Connor", "Cooper", "Craig", "Damian", "David", "Dean", "Derek", "Dominic",
        "Drew", "Dylan", "Edward", "Eric", "Ethan", "Evan", "Felix", "Frank",
        "Gabriel", "Gavin", "George", "Grant", "Gregory", "Harvey", "Henry", "Hugo",
        "Ian", "Isaac", "Ivan", "Jack", "Jacob", "Jason", "Jasper", "Joel",
        "John", "Jonah", "Jonathan", "Jordan", "Joseph", "Joshua", "Julian", "Justin",
        "Keith", "Kenneth", "Kevin", "Kyle", "Lance", "Leo", "Liam", "Logan",
        "Louis", "Lucas", "Luke", "Marcus", "Martin", "Mason", "Matthew", "Michael",
        "Miles", "Nathan", "Neil", "Noah", "Oliver", "Owen", "Patrick", "Paul",
        "Peter", "Philip", "Quentin", "Ralph", "Raymond", "Reid", "Richard", "Robert",
        "Ronan", "Ross", "Ryan", "Scott", "Sean", "Sebastian", "Seth", "Simon",
        "Spencer", "Stanley", "Stephen", "Theodore", "Thomas", "Travis", "Vincent", "Wesley",
    )
    private val lastNames = listOf(
        "Stone", "Reed", "Cole", "Ward", "Hill", "Fox", "Brooks", "Gray",
        "West", "Lane", "Wood", "Hart", "Price", "Hale", "King", "Cross",
        "Adams", "Allen", "Anderson", "Armstrong", "Bailey", "Baker", "Barnes", "Barrett",
        "Bennett", "Bishop", "Black", "Blair", "Bowman", "Bradley", "Brown", "Bryant",
        "Burke", "Burns", "Butler", "Campbell", "Carter", "Chapman", "Clark", "Collins",
        "Cooper", "Cox", "Crawford", "Daniels", "Davis", "Dawson", "Dean", "Dixon",
        "Douglas", "Duncan", "Edwards", "Elliott", "Ellis", "Evans", "Fisher", "Fletcher",
        "Ford", "Foster", "Fowler", "Franklin", "Freeman", "Fuller", "Gibson", "Gordon",
        "Graham", "Grant", "Green", "Griffin", "Hall", "Hamilton", "Hansen", "Hardy",
        "Harper", "Harris", "Harrison", "Hawkins", "Hayes", "Henderson", "Henry", "Hicks",
        "Hoffman", "Holland", "Holmes", "Howard", "Hudson", "Hughes", "Hunt", "Hunter",
        "Jackson", "Jacobs", "Jenkins", "Johnson", "Jones", "Kelly", "Kennedy", "Knight",
        "Lawrence", "Lewis", "Long", "Marshall", "Martin", "Mason", "Matthews", "Miller",
        "Mitchell", "Morgan", "Morris", "Murphy", "Nelson", "Nolan", "Palmer", "Parker",
        "Pearson", "Perry", "Peters", "Phillips", "Porter", "Powell", "Quinn", "Reynolds",
        "Richards", "Riley", "Roberts", "Robinson", "Rogers", "Russell", "Sanders", "Scott",
        "Shaw", "Simmons", "Smith", "Stewart", "Sullivan", "Taylor", "Turner", "Walker",
    )

    fun threshold(rank: NpcRank): Double = SquadConfig.experienceThresholds()[rank.ordinal].toDouble()
    fun rank(xp: Double): NpcRank = NpcRank.entries.lastOrNull { xp >= threshold(it) } ?: NpcRank.RECRUIT
    fun next(rank: NpcRank): Double? = NpcRank.entries.getOrNull(rank.ordinal + 1)?.let(::threshold)
    fun name(id: UUID): String {
        val first = firstNames[Math.floorMod(id.mostSignificantBits, firstNames.size.toLong()).toInt()]
        val last = lastNames[Math.floorMod(id.leastSignificantBits, lastNames.size.toLong()).toInt()]
        return "$first $last"
    }
}
