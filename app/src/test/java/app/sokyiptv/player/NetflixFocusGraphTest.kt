package app.sokyiptv.player

import app.sokyiptv.ui.vod.NetflixFocusGraph
import app.sokyiptv.ui.vod.NetflixTarget
import app.sokyiptv.ui.vod.NetflixDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class NetflixFocusGraphTest {
 @Test fun navigation() {
  fun step(t:NetflixTarget,d:NetflixDirection,e:Boolean=true,n:Boolean=true)=
      NetflixFocusGraph.next(t,d,e,n)
  assertEquals(NetflixTarget.TIMELINE,step(NetflixTarget.PLAY,NetflixDirection.DOWN))
  assertEquals(NetflixTarget.EPISODES,step(NetflixTarget.TIMELINE,NetflixDirection.DOWN))
  assertEquals(NetflixTarget.TRACKS,step(NetflixTarget.EPISODES,NetflixDirection.RIGHT))
  assertEquals(NetflixTarget.NEXT,step(NetflixTarget.TRACKS,NetflixDirection.RIGHT))
  assertEquals(NetflixTarget.TIMELINE,step(NetflixTarget.NEXT,NetflixDirection.UP))
  assertEquals(NetflixTarget.PLAY,step(NetflixTarget.TIMELINE,NetflixDirection.UP))
  assertEquals(NetflixTarget.TRACKS,step(NetflixTarget.TIMELINE,NetflixDirection.DOWN,false,false))
  assertEquals(NetflixTarget.TIMELINE,step(NetflixTarget.TIMELINE,NetflixDirection.LEFT))
  for(t in NetflixTarget.entries) if(t!=NetflixTarget.TIMELINE)
    assert(NetflixDirection.entries.any { step(t,it)!=t })
 }
}
