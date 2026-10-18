package app.folio.reader

/** Original Danish story. Choices change reactions, never the price or unlock requirements. */
internal data class CafeChoice(val caption: String, val reaction: String)
internal data class CafeChapter(
    val id: Int, val title: String, val visits: Int, val treats: Int,
    val opening: List<String>, val choices: List<CafeChoice>, val ending: List<String>,
    val pose: String = "idle"
) {
    val lastPage get() = opening.size + 1 + ending.size
    fun text(page: Int, choice: Int): String = when {
        page < opening.size -> opening[page]
        page == opening.size -> "Hvad gør du?"
        page == opening.size + 1 -> choices[choice.coerceIn(choices.indices)].reaction
        else -> ending[(page - opening.size - 2).coerceIn(ending.indices)]
    }
}

internal enum class CafeAction(val title: String, val cost: Int, val caption: String) {
    TREAT("Giv en godbid", 1, "Miso snuser. Godkender. Spinder."),
    PAW("Giv pote", 2, "En højtidelig hilsen fra læsechefen."),
    BOOKMARK("Hent bogmærket", 3, "Bogmærket er tilbage. Næsten uden bidemærker.")
}

internal object MisoStory {
    val chapters = listOf(
        CafeChapter(0, "En meget vigtig kat", 0, 0, listOf(
            "Du har lige fundet din bog, da du opdager, at lænestolen er optaget.",
            "Miso ligger midt på hynden. Den ene pote hviler på armlænet. Halen bevæger sig langsomt, som om han allerede leder et vigtigt møde.",
            "Han åbner det ene øje. Du standser foran stolen med bogen i hånden."
        ), listOf(
            CafeChoice("»Må jeg også være her, chef?«", "Miso retter sig lidt op. Det var åbenbart den korrekte titel. Han flytter sin pote et par centimeter. En meget generøs indrømmelse."),
            CafeChoice("»Jeg tror, du har fundet min plads.«", "Miso ser ned på hynden. Så ser han op på dig og lægger hovedet på skrå. Det ligner mere en invitation end en undskyldning.")
        ), listOf(
            "Du sætter dig forsigtigt. Miso rykker til siden, lige nok til at I begge kan være der. Da du åbner bogen, lægger han en pote nederst på siden.",
            "»Så du holder øje med, hvor langt jeg er kommet?«\n\nEt kort »mrrp« er hele svaret.",
            "Du læser den første linje. Miso lukker øjnene, men poten bliver liggende. Caféen har fået en læsechef."
        )),
        CafeChapter(1, "Bogmærketyven", 2, 2, listOf(
            "Dit bogmærke er væk. Det samme er det grønne fra bordet og det med den lille kvast. På gulvet ligger et enkelt orange hår.",
            "Under Misos pude finder du tre bogmærker. Han stiller sig foran dem med en mine, der antyder, at du har åbnet et meget fortroligt arkiv.",
            "Han lægger forsigtigt en pote på din bog. Så peger næsen mod samlingen. Måske er det hans måde at passe på alles pladser."
        ), listOf(
            CafeChoice("»Har du åbnet et bogmærkebibliotek?«", "Miso sætter sig helt rank. Han skubber det grønne bogmærke frem, men holder din kvast fast under poten. Udlånsreglerne er tydeligvis strenge."),
            CafeChoice("»Vi kan lave en kurv til dem.«", "Du stiller en lille kurv ved stolen. Miso undersøger kanten og lægger ét bogmærke i. Så fisker han det op igen. Kvalitetskontrol tager tid.")
        ), listOf(
            "I lægger bogmærkerne i kurven sammen. Dit eget bliver liggende mellem hans forpoter, indtil du rækker hånden frem.",
            "Miso slipper kvasten. Du sætter bogmærket i bogen og takker ham for hjælpen. Han giver kurven et tilfreds skub med næsen.",
            "Senere opdager du et fjerde bogmærke i kurven. Det er en ren papirstrimmel med ét meget tydeligt poteaftryk."
        ), "bookmark"),
        CafeChapter(2, "En hjælpende pote", 5, 5, listOf(
            "Miso har fået en ny opgave. Hver gang nogen nærmer sig læsehjørnet, løfter han en pote. Han holder den svævende med stor koncentration.",
            "Du sætter dig på hug. Poten stiger en smule højere. Hans blik veksler mellem din hånd og godbidskrukken.",
            "»Er det sådan, man bliver budt velkommen her?«\n\nMiso svarer med et bestemt lille pip."
        ), listOf(
            CafeChoice("Ræk hånden frem til et højtideligt håndtryk.", "Miso placerer poten i din hånd. Så tilføjer han den anden. Du står nu med begge forpoter, mens han ser forbavsende stolt ud."),
            CafeChoice("Løft hånden til en lille highfive.", "Miso rammer håndfladen med en blød pote. Han prøver straks igen, denne gang lidt for ivrigt, og må rette balancen med halen.")
        ), listOf(
            "På underkoppen ligger en lille godbid fra tidligere. Du lægger den foran ham som tak for den fine velkomst.",
            "Miso spiser og begynder straks at øve sig igen. Denne gang løfter han poten mod sin egen skygge.",
            "Da du åbner bogen, hviler hans pote kort på din hånd. Velkomsten er vist godkendt."
        ), "paw"),
        CafeChapter(3, "Den store plan", 9, 10, listOf(
            "Der ligger en pude ved stolen, en pude foran stolen og en pude oven på den første pude. Miso står midt i arbejdet.",
            "Han skubber bogmærkekurven på plads. Rykker den tilbage. Retter et tæppe med tænderne. Det er tydeligt, at han har en plan.",
            "Du finder en ledig krog på bordet til din bog. Miso ser bekymret på det ene skæve pudehjørne."
        ), listOf(
            CafeChoice("»Den her pude ser dejlig ud.«", "Du banker let på den nærmeste pude. Miso undersøger den, træder to gange på midten og ser op. Din vurdering er nu fagligt bekræftet."),
            CafeChoice("»Skal vi gøre plads til os begge?«", "Du flytter én pude og glatter tæppet. Miso følger hver bevægelse. Så skubber han sin egen pude helt hen til din.")
        ), listOf(
            "Du sætter dig. Miso bliver stående et øjeblik og betragter hjørnet. Så retter han endnu en gang på tæppet.",
            "»Du må gerne holde fri, chef.«\n\nHan blinker, men vender tilbage til kurven. Et bogmærke stikker ud over kanten.",
            "Du lader bogen stå åben, mens du venter. Miso har vist besluttet, at alt skal være helt perfekt, før han kan sætte sig."
        )),
        CafeChapter(4, "Når det går i kludder", 14, 17, listOf(
            "Noget orange forsvinder bag stolen. Det er ikke Miso. Det er en garnende. Miso sidder lidt længere inde, viklet ind i resten.",
            "Rundt om ham ligger bogmærkerne i en ujævn cirkel. Tæppet er gledet ned. Den store pude står på højkant.",
            "Da du bøjer dig, sænker han ørerne. Han skubber et bogmærke mod dig og trækker poten til sig igen."
        ), listOf(
            CafeChoice("»Vi kan godt rydde op sammen.«", "Du løsner den nærmeste løkke. Miso holder helt stille og hjælper derefter ved at lægge poten på garnenden. Denne gang virker det faktisk."),
            CafeChoice("»Kom her. Rodet kan vente lidt.«", "Du sætter dig på gulvet og lægger hånden ved siden af dig. Miso kommer forsigtigt frem. En garnende følger med som et meget lille slæb.")
        ), listOf(
            "Du får det sidste garn af hans pote. Der er ingen, der skælder ud. Kun et tæppe, et par skæve puder og en bog, der stadig venter.",
            "»Jeg kom for at læse sammen med dig. Du behøver ikke ordne det hele.«\n\nMiso lægger hagen på dit knæ.",
            "I bliver siddende lidt. Da du endelig samler bogmærkerne op, følger Miso med. Han overlader det meste af oprydningen til dig."
        ), "shy"),
        CafeChapter(5, "Din plads er her", 20, 25, listOf(
            "I dag ligger der kun én pude i stolen. Tæppet er lidt skævt. Bogmærkekurven står, hvor den nu engang står.",
            "Miso venter ved siden af din bog. Da du kommer, tager han et bogmærke op og lægger det foran dig. Ingen gemmesteder denne gang.",
            "Du sætter dig. Han ser fra den ledige plads til dig, som om der mangler en sidste godkendelse."
        ), listOf(
            CafeChoice("»Din plads er lige her.«", "Du klapper let på hynden. Miso kommer op med det samme. Han drejer en enkelt omgang og lægger sig ind til dit ben."),
            CafeChoice("»Skal vi læse et kapitel, chef?«", "Miso svarer med sit velkendte »mrrp«. Så skubber han bogen en anelse nærmere og lægger sig. Dagens møde er begyndt.")
        ), listOf(
            "Du slår op på den rigtige side. Miso lægger ikke længere poten på teksten. Han ved, at bogmærket nok skal holde styr på den.",
            "Udenfor glider en sky forbi vinduet. Herinde følger et roligt spind med gennem de første sider. Ingen af jer har travlt.",
            "Miso strækker sig og lægger hovedet til rette. Han har stadig en vigtig opgave i caféen.\n\nHan skal bare være Miso."
        ), "rest")
    )
}
