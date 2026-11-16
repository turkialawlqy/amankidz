// خدمة المباريات (تبويب Matches): تستخدم مفتاح الاختبار العام المجاني
// الموثّق من TheSportsDB. للاستخدام الفعلي بموثوقية أعلى يمكن لاحقاً استبداله
// بمفتاح مدفوع (اشتراك Patreon) من نفس الخدمة دون تغيير باقي الكود.

export interface MatchFixture {
  id: string;
  league: string;
  homeTeam: string;
  awayTeam: string;
  homeBadge: string | null;
  awayBadge: string | null;
  kickoffAt: Date | null;
}

const SPORTSDB_KEY = '123';
const SPORTSDB_BASE = `https://www.thesportsdb.com/api/v1/json/${SPORTSDB_KEY}`;

const POPULAR_LEAGUES = [
  { id: '4328', name: 'الدوري الإنجليزي الممتاز' },
  { id: '4335', name: 'الدوري الإسباني' },
];

/** يجلب المباريات القادمة لأشهر الدوريات ويرتبها زمنياً */
export async function getUpcomingMatches(limit = 12): Promise<MatchFixture[]> {
  try {
    const perLeague = await Promise.all(
      POPULAR_LEAGUES.map(async (league) => {
        try {
          const res = await fetch(`${SPORTSDB_BASE}/eventsnextleague.php?id=${league.id}`);
          if (!res.ok) return [];
          const json = await res.json();
          const events = json.events || [];
          return events.map(
            (e: any): MatchFixture => ({
              id: String(e.idEvent),
              league: league.name,
              homeTeam: e.strHomeTeam,
              awayTeam: e.strAwayTeam,
              homeBadge: e.strHomeTeamBadge || null,
              awayBadge: e.strAwayTeamBadge || null,
              kickoffAt: e.strTimestamp ? new Date(e.strTimestamp) : null,
            })
          );
        } catch (err) {
          console.warn(`getUpcomingMatches (${league.name}) error:`, err);
          return [];
        }
      })
    );

    const merged = perLeague.flat();
    merged.sort((a, b) => (a.kickoffAt?.getTime() || 0) - (b.kickoffAt?.getTime() || 0));
    return merged.slice(0, limit);
  } catch (err) {
    console.warn('getUpcomingMatches error:', err);
    return [];
  }
}
